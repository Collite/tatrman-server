"""Phase 2.4 §F+§G — PlanNode → Polars LazyFrame + Expression converter."""

from __future__ import annotations

import polars as pl
import pytest
from org.tatrman.plan.v1 import plan_pb2

from workers_steropes.config import WorkspaceConfig
from workers_steropes.converter import (
    PlanToPolars,
    UnsupportedExpression,
    UnsupportedTableScan,
    WorkspaceNotFound,
    convert_expr,
)
from workers_steropes.workspace import WorkspaceStore


def _cfg() -> WorkspaceConfig:
    return WorkspaceConfig(
        max_sessions=10,
        max_dfs_per_session=10,
        max_bytes_per_df=10_000_000,
        max_total_bytes=100_000_000,
        idle_ttl_seconds=60,
        sweeper_interval_seconds=10,
    )


def _df() -> pl.DataFrame:
    return pl.DataFrame(
        {
            "id": [1, 2, 3, 4, 5],
            "amount": [10.0, 20.0, 30.0, 40.0, 50.0],
            "region": ["EU", "EU", "US", "US", "EU"],
        }
    )


def _workspace_ref(name: str) -> plan_pb2.PlanNode:
    return plan_pb2.PlanNode(workspace_ref=plan_pb2.WorkspaceRef(workspace_name=name))


def _column_ref(name: str) -> plan_pb2.Expression:
    return plan_pb2.Expression(column_ref=plan_pb2.ColumnRef(name=name))


def _int_lit(v: int) -> plan_pb2.Expression:
    return plan_pb2.Expression(literal=plan_pb2.Literal(int_value=v, type="int"))


def _float_lit(v: float) -> plan_pb2.Expression:
    return plan_pb2.Expression(literal=plan_pb2.Literal(float_value=v, type="float"))


def _string_lit(v: str) -> plan_pb2.Expression:
    return plan_pb2.Expression(literal=plan_pb2.Literal(string_value=v, type="text"))


def _fn(op: str, *operands: plan_pb2.Expression, result_type: str = "bool") -> plan_pb2.Expression:
    return plan_pb2.Expression(
        function=plan_pb2.FunctionCall(operation=op, operands=list(operands)),
        result_type=result_type,
    )


# ----- §F PlanNode visitors -----


@pytest.mark.asyncio
async def test_workspace_ref_loads_stored_dataframe():
    store = WorkspaceStore(_cfg())
    await store.put("s1", "q1", _df())
    converter = PlanToPolars(store, session_id="s1")
    lf = await converter.convert(_workspace_ref("q1"))
    out = lf.collect()
    assert out.shape == (5, 3)


@pytest.mark.asyncio
async def test_workspace_ref_missing_raises_workspace_not_found():
    store = WorkspaceStore(_cfg())
    converter = PlanToPolars(store, session_id="s1")
    with pytest.raises(WorkspaceNotFound):
        await converter.convert(_workspace_ref("nope"))


@pytest.mark.asyncio
async def test_table_scan_raises_unsupported_table_scan():
    converter = PlanToPolars(None, session_id="s1")
    plan = plan_pb2.PlanNode(table_scan=plan_pb2.TableScanNode())
    with pytest.raises(UnsupportedTableScan):
        await converter.convert(plan)


@pytest.mark.asyncio
async def test_filter_amount_gt_25_returns_three_rows():
    store = WorkspaceStore(_cfg())
    await store.put("s1", "q1", _df())
    converter = PlanToPolars(store, session_id="s1")
    plan = plan_pb2.PlanNode(
        filter=plan_pb2.FilterNode(
            input=_workspace_ref("q1"),
            condition=_fn("gt", _column_ref("amount"), _float_lit(25.0)),
        )
    )
    out = (await converter.convert(plan)).collect()
    assert out.shape[0] == 3
    assert out["amount"].to_list() == [30.0, 40.0, 50.0]


@pytest.mark.asyncio
async def test_project_renames_and_selects():
    store = WorkspaceStore(_cfg())
    await store.put("s1", "q1", _df())
    converter = PlanToPolars(store, session_id="s1")
    plan = plan_pb2.PlanNode(
        project=plan_pb2.ProjectNode(
            input=_workspace_ref("q1"),
            expressions=[
                plan_pb2.NamedExpression(expression=_column_ref("region"), alias="r"),
                plan_pb2.NamedExpression(expression=_column_ref("amount"), alias="amt"),
            ],
        )
    )
    out = (await converter.convert(plan)).collect()
    assert out.columns == ["r", "amt"]
    assert out.shape == (5, 2)


@pytest.mark.asyncio
async def test_aggregate_sum_amount_by_region():
    store = WorkspaceStore(_cfg())
    await store.put("s1", "q1", _df())
    converter = PlanToPolars(store, session_id="s1")
    plan = plan_pb2.PlanNode(
        aggregate=plan_pb2.AggregateNode(
            input=_workspace_ref("q1"),
            group_keys=[plan_pb2.ColumnRef(name="region")],
            aggregates=[
                plan_pb2.AggregateCall(function="sum", args=[plan_pb2.ColumnRef(name="amount")], alias="total"),
            ],
        )
    )
    out = (await converter.convert(plan)).collect().sort("region")
    assert set(out.columns) == {"region", "total"}
    eu_total = out.filter(pl.col("region") == "EU")["total"][0]
    us_total = out.filter(pl.col("region") == "US")["total"][0]
    assert eu_total == 80.0  # 10 + 20 + 50
    assert us_total == 70.0  # 30 + 40


@pytest.mark.asyncio
async def test_sort_descending_by_amount():
    store = WorkspaceStore(_cfg())
    await store.put("s1", "q1", _df())
    converter = PlanToPolars(store, session_id="s1")
    plan = plan_pb2.PlanNode(
        sort=plan_pb2.SortNode(
            input=_workspace_ref("q1"),
            sort_keys=[plan_pb2.SortKey(column=plan_pb2.ColumnRef(name="amount"), descending=True)],
        )
    )
    out = (await converter.convert(plan)).collect()
    assert out["amount"].to_list() == [50.0, 40.0, 30.0, 20.0, 10.0]


@pytest.mark.asyncio
async def test_limit_offset_slices():
    store = WorkspaceStore(_cfg())
    await store.put("s1", "q1", _df())
    converter = PlanToPolars(store, session_id="s1")
    plan = plan_pb2.PlanNode(
        limit_offset=plan_pb2.LimitOffsetNode(
            input=_workspace_ref("q1"),
            limit=2,
            offset=1,
        )
    )
    out = (await converter.convert(plan)).collect()
    assert out.shape[0] == 2
    assert out["id"].to_list() == [2, 3]


@pytest.mark.asyncio
async def test_join_inner_on_id():
    store = WorkspaceStore(_cfg())
    await store.put("s1", "left", pl.DataFrame({"id": [1, 2, 3], "x": ["a", "b", "c"]}))
    await store.put("s1", "right", pl.DataFrame({"id": [2, 3, 4], "y": ["B", "C", "D"]}))
    converter = PlanToPolars(store, session_id="s1")
    plan = plan_pb2.PlanNode(
        join=plan_pb2.JoinNode(
            left=_workspace_ref("left"),
            right=_workspace_ref("right"),
            join_type=plan_pb2.INNER,
            condition=_fn("eq", _column_ref("id"), _column_ref("id")),
        )
    )
    out = (await converter.convert(plan)).collect().sort("id")
    assert out.shape[0] == 2
    assert out["id"].to_list() == [2, 3]


# ----- §G expression converter -----


def test_expr_literal_int():
    out = convert_expr(_int_lit(42))
    # `pl.lit` is a scalar; broadcast across rows via with_columns.
    df = pl.DataFrame({"x": [1, 2]}).with_columns(out.alias("v"))
    assert df["v"].to_list() == [42, 42]


def test_expr_arithmetic_add():
    e = _fn("add", _column_ref("amount"), _float_lit(1.0), result_type="float")
    out = convert_expr(e)
    df = pl.DataFrame({"amount": [1.0, 2.0]}).select(out.alias("v"))
    assert df["v"].to_list() == [2.0, 3.0]


def test_expr_logical_and():
    e = _fn(
        "and",
        _fn("gt", _column_ref("a"), _int_lit(1)),
        _fn("lt", _column_ref("a"), _int_lit(5)),
    )
    out = convert_expr(e)
    df = pl.DataFrame({"a": [0, 2, 6]}).filter(out)
    assert df["a"].to_list() == [2]


def test_expr_is_null_and_coalesce():
    e_isnull = _fn("is_null", _column_ref("v"))
    df = pl.DataFrame({"v": [1, None, 3]}).select(convert_expr(e_isnull).alias("n"))
    assert df["n"].to_list() == [False, True, False]

    e_coalesce = _fn("coalesce", _column_ref("v"), _int_lit(0))
    df2 = pl.DataFrame({"v": [1, None, 3]}).select(convert_expr(e_coalesce).alias("c"))
    assert df2["c"].to_list() == [1, 0, 3]


def test_expr_in_set():
    e = _fn("in", _column_ref("region"), _string_lit("EU"), _string_lit("US"))
    out = convert_expr(e)
    df = pl.DataFrame({"region": ["EU", "AS", "US"]}).select(out.alias("hit"))
    assert df["hit"].to_list() == [True, False, True]


def test_expr_like_translates_pattern():
    e = _fn("like", _column_ref("name"), _string_lit("Al%"))
    out = convert_expr(e)
    df = pl.DataFrame({"name": ["Alice", "Bob", "Aladdin"]}).select(out.alias("hit"))
    assert df["hit"].to_list() == [True, False, True]


def test_expr_unknown_operator_raises():
    e = _fn("does_not_exist", _column_ref("x"))
    with pytest.raises(UnsupportedExpression):
        convert_expr(e)


def test_expr_parameter_binds_from_context():
    e = plan_pb2.Expression(parameter=plan_pb2.ParameterRef(name="threshold"))
    out = convert_expr(e, parameters={"threshold": 100})
    df = pl.DataFrame({"x": [1]}).select(out.alias("v"))
    assert df["v"].to_list() == [100]


def test_expr_unbound_parameter_raises():
    e = plan_pb2.Expression(parameter=plan_pb2.ParameterRef(name="missing"))
    with pytest.raises(UnsupportedExpression):
        convert_expr(e, parameters={})
