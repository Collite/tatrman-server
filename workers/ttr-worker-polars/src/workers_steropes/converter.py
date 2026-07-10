"""PlanNode → Polars LazyFrame converter.

The Polars Worker's heart. Walks a `org.tatrman.plan.v1.PlanNode` and
produces a `pl.LazyFrame`. Per-RelOp handlers cover the v1 RelOp set;
expressions go through `convert_expr`.

Out-of-scope (raise loud errors):
  * `TableScan` — Polars Worker doesn't speak SQL; the dispatcher's
    routing-by-content rule (Phase 2.4 §B) prevents `TableScan`-rooted
    plans from reaching us. Defensive guard with `UnsupportedTableScan`.

Phase 2.4 contract: workers stay dumb (Bora-confirmed). The converter
does not attempt to decorate output columns with `display_label` /
`value_labels` Arrow field metadata; theseus-mcp's side-channel handles
that for both engines uniformly (Phase 2.2 convention).
"""

from __future__ import annotations

import datetime as _dt
import logging
from typing import TYPE_CHECKING

import polars as pl
from org.tatrman.plan.v1 import plan_pb2

if TYPE_CHECKING:
    from workers_steropes.workspace import WorkspaceStore

logger = logging.getLogger("workers_steropes.converter")


# ----- Errors -----


class WorkspaceNotFound(Exception):
    """workspace_ref.workspace_name not in the store for this session."""

    code: str = "workspace_not_found"


class UnsupportedTableScan(Exception):
    """A TableScan reached the Polars Worker — should not happen given dispatcher routing."""

    code: str = "unsupported_table_scan"


class UnsupportedExpression(Exception):
    """Expression / function operator outside the v1 standardised set."""

    code: str = "unsupported_expression"


class UnsupportedNode(Exception):
    """PlanNode oneof case the converter doesn't yet implement."""

    code: str = "unsupported_node"


# ----- Plan converter -----


class PlanToPolars:
    """Stateful walker — needs the workspace store + session_id to resolve workspace_ref."""

    def __init__(
        self,
        store: WorkspaceStore | None,
        session_id: str,
        parameters: dict[str, object] | None = None,
    ) -> None:
        self._store = store
        self._session_id = session_id
        self._parameters = parameters or {}

    async def convert(self, node: plan_pb2.PlanNode) -> pl.LazyFrame:
        case = node.WhichOneof("node")
        if case is None:
            raise UnsupportedNode("PlanNode oneof was not set")
        method = getattr(self, f"_visit_{case}", None)
        if method is None:
            raise UnsupportedNode(f"PlanNode oneof case '{case}' has no handler")
        return await method(node)

    # ----- Per-RelOp visitors -----

    async def _visit_workspace_ref(self, node: plan_pb2.PlanNode) -> pl.LazyFrame:
        if self._store is None:
            raise WorkspaceNotFound("workspace_ref encountered without a workspace store available")
        name = node.workspace_ref.workspace_name
        entry = await self._store.get(self._session_id, name)
        if entry is None:
            raise WorkspaceNotFound(
                f"workspace '{name}' not found in session '{self._session_id}'",
            )
        return entry.df.lazy()

    async def _visit_table_scan(self, _node: plan_pb2.PlanNode) -> pl.LazyFrame:
        raise UnsupportedTableScan(
            "Polars Worker does not execute TableScan; dispatcher routing should have prevented this",
        )

    async def _visit_project(self, node: plan_pb2.PlanNode) -> pl.LazyFrame:
        input_lf = await self.convert(node.project.input)
        exprs = []
        for named in node.project.expressions:
            expr = convert_expr(named.expression, parameters=self._parameters)
            if named.alias:
                expr = expr.alias(named.alias)
            exprs.append(expr)
        return input_lf.select(exprs)

    async def _visit_filter(self, node: plan_pb2.PlanNode) -> pl.LazyFrame:
        input_lf = await self.convert(node.filter.input)
        condition = convert_expr(node.filter.condition, parameters=self._parameters)
        return input_lf.filter(condition)

    async def _visit_join(self, node: plan_pb2.PlanNode) -> pl.LazyFrame:
        left = await self.convert(node.join.left)
        right = await self.convert(node.join.right)
        how = _join_how(node.join.join_type)
        # Conditions are restricted to equi-joins on column refs at v1
        # (TransDSL multi-core Cartesian shape elaborates the rest later).
        # If condition is unset → cross join.
        if node.join.HasField("condition"):
            left_keys, right_keys = _equi_join_keys(node.join.condition)
            return left.join(right, left_on=left_keys, right_on=right_keys, how=how)
        return left.join(right, how="cross")

    async def _visit_aggregate(self, node: plan_pb2.PlanNode) -> pl.LazyFrame:
        input_lf = await self.convert(node.aggregate.input)
        group_keys = [pl.col(c.name) for c in node.aggregate.group_keys]
        aggs = [_aggregate_call(call) for call in node.aggregate.aggregates]
        if not group_keys:
            # Pure aggregate — no group-by axis. Polars supports this via
            # .select() over aggregate exprs.
            return input_lf.select(aggs)
        return input_lf.group_by(group_keys).agg(aggs)

    async def _visit_sort(self, node: plan_pb2.PlanNode) -> pl.LazyFrame:
        input_lf = await self.convert(node.sort.input)
        keys = [pl.col(k.column.name) for k in node.sort.sort_keys]
        descending = [k.descending for k in node.sort.sort_keys]
        nulls_last = [not k.nulls_first for k in node.sort.sort_keys]
        return input_lf.sort(keys, descending=descending, nulls_last=nulls_last)

    async def _visit_limit_offset(self, node: plan_pb2.PlanNode) -> pl.LazyFrame:
        input_lf = await self.convert(node.limit_offset.input)
        offset = int(node.limit_offset.offset) if node.limit_offset.HasField("offset") else 0
        if node.limit_offset.HasField("limit"):
            return input_lf.slice(offset, int(node.limit_offset.limit))
        # Limit unset → from offset to end.
        return input_lf.slice(offset)

    async def _visit_values(self, node: plan_pb2.PlanNode) -> pl.LazyFrame:
        cols = [c.name for c in node.values.output_columns]
        if not cols:
            return pl.LazyFrame()
        # Each Row is a list of Literal cells in the same order as output_columns.
        cells_per_col: list[list[object]] = [[] for _ in cols]
        for row in node.values.rows:
            for i, lit in enumerate(row.cells):
                cells_per_col[i].append(_literal_to_python(lit))
        data = {col: cells_per_col[i] for i, col in enumerate(cols)}
        return pl.LazyFrame(data)

    async def _visit_subquery(self, node: plan_pb2.PlanNode) -> pl.LazyFrame:
        # Subqueries inline — Polars LazyFrames compose. Alias is informational
        # for downstream column-ref disambiguation; no first-class concept in
        # Polars' lazy API, so we just return the inner LazyFrame.
        return await self.convert(node.subquery.subquery)


# ----- Helpers -----


def _join_how(join_type: int) -> str:
    if join_type == plan_pb2.INNER:
        return "inner"
    if join_type == plan_pb2.LEFT:
        return "left"
    if join_type == plan_pb2.RIGHT:
        return "right"
    if join_type == plan_pb2.FULL:
        return "full"
    return "inner"


def _equi_join_keys(condition: plan_pb2.Expression) -> tuple[list[str], list[str]]:
    """Extract left/right column-name lists from a top-level eq (or AND of eqs)."""
    if condition.HasField("function"):
        op = condition.function.operation.lower()
        if op == "eq" and len(condition.function.operands) == 2:
            l, r = condition.function.operands  # noqa: E741
            if l.HasField("column_ref") and r.HasField("column_ref"):
                return [l.column_ref.name], [r.column_ref.name]
        if op == "and":
            ls: list[str] = []
            rs: list[str] = []
            for sub in condition.function.operands:
                sl, sr = _equi_join_keys(sub)
                ls.extend(sl)
                rs.extend(sr)
            return ls, rs
    raise UnsupportedExpression(
        f"join condition must be an equi-join (eq or AND of eqs); got {condition.WhichOneof('expr')}",
    )


def _aggregate_call(call: plan_pb2.AggregateCall) -> pl.Expr:
    fn = call.function.lower()
    args = call.args
    arg = pl.col(args[0].name) if args else pl.lit(1)
    if fn in {"count", "count_star"}:
        if not args:
            expr = pl.len()
        elif call.distinct:
            expr = arg.n_unique()
        else:
            expr = arg.count()
    elif fn == "count_distinct":
        expr = arg.n_unique()
    elif fn == "sum":
        expr = arg.sum()
    elif fn in {"avg", "mean"}:
        expr = arg.mean()
    elif fn == "min":
        expr = arg.min()
    elif fn == "max":
        expr = arg.max()
    else:
        raise UnsupportedExpression(f"aggregate function '{call.function}' not supported")
    if call.alias:
        expr = expr.alias(call.alias)
    return expr


def _literal_to_python(lit: plan_pb2.Literal) -> object | None:
    if lit.is_null:
        return None
    case = lit.WhichOneof("value")
    if case == "string_value":
        return lit.string_value
    if case == "int_value":
        return int(lit.int_value)
    if case == "float_value":
        return float(lit.float_value)
    if case == "bool_value":
        return bool(lit.bool_value)
    if case == "datetime_value":
        # ISO-8601 string. Polars accepts it as-is when constructing a series;
        # caller may need an explicit dtype hint for round-tripping.
        return _dt.datetime.fromisoformat(lit.datetime_value)
    return None


# ----- Expression converter (Section G) -----

_BINARY_ARITH_OPS = {
    "add": lambda a, b: a + b,
    "subtract": lambda a, b: a - b,
    "multiply": lambda a, b: a * b,
    "divide": lambda a, b: a / b,
    "modulo": lambda a, b: a % b,
}

_BINARY_CMP_OPS = {
    "eq": lambda a, b: a == b,
    "ne": lambda a, b: a != b,
    "lt": lambda a, b: a < b,
    "le": lambda a, b: a <= b,
    "gt": lambda a, b: a > b,
    "ge": lambda a, b: a >= b,
}

_LOGICAL_OPS = {
    "and": lambda *xs: _fold(xs, lambda a, b: a & b),
    "or": lambda *xs: _fold(xs, lambda a, b: a | b),
}


def _fold(items, op):
    it = iter(items)
    acc = next(it)
    for x in it:
        acc = op(acc, x)
    return acc


def convert_expr(
    expr: plan_pb2.Expression,
    parameters: dict[str, object] | None = None,
) -> pl.Expr:
    """Convert a single Expression to a Polars expression."""
    parameters = parameters or {}
    case = expr.WhichOneof("expr")
    if case == "literal":
        return pl.lit(_literal_to_python(expr.literal))
    if case == "column_ref":
        return pl.col(expr.column_ref.name)
    if case == "parameter":
        name = expr.parameter.name
        if name not in parameters:
            raise UnsupportedExpression(f"parameter '{name}' not bound")
        return pl.lit(parameters[name])
    if case == "cast":
        inner = convert_expr(expr.cast.value, parameters=parameters)
        return inner.cast(_polars_dtype(expr.cast.target_type))
    if case == "function":
        return _convert_function(expr.function, parameters)
    raise UnsupportedExpression(f"Expression oneof case '{case}' is not supported")


def _convert_function(
    fn: plan_pb2.FunctionCall,
    parameters: dict[str, object],
) -> pl.Expr:
    op = fn.operation.lower()
    operands = [convert_expr(o, parameters=parameters) for o in fn.operands]

    if op in _BINARY_ARITH_OPS:
        if len(operands) != 2:
            raise UnsupportedExpression(f"'{op}' expects 2 operands, got {len(operands)}")
        return _BINARY_ARITH_OPS[op](operands[0], operands[1])
    if op == "negate":
        if len(operands) != 1:
            raise UnsupportedExpression(f"'negate' expects 1 operand, got {len(operands)}")
        return -operands[0]
    if op in _BINARY_CMP_OPS:
        if len(operands) != 2:
            raise UnsupportedExpression(f"'{op}' expects 2 operands, got {len(operands)}")
        return _BINARY_CMP_OPS[op](operands[0], operands[1])
    if op in _LOGICAL_OPS:
        if not operands:
            raise UnsupportedExpression(f"'{op}' expects at least 1 operand")
        return _LOGICAL_OPS[op](*operands)
    if op == "not":
        if len(operands) != 1:
            raise UnsupportedExpression(f"'not' expects 1 operand, got {len(operands)}")
        return ~operands[0]
    if op == "is_null":
        return operands[0].is_null()
    if op == "is_not_null":
        return operands[0].is_not_null()
    if op == "between":
        if len(operands) != 3:
            raise UnsupportedExpression(f"'between' expects 3 operands, got {len(operands)}")
        return operands[0].is_between(operands[1], operands[2])
    if op == "in":
        if len(operands) < 2:
            raise UnsupportedExpression("'in' expects at least 2 operands")
        # `is_in` expects an iterable of literal values, not a list of
        # expressions. Extract Python values from literal operands.
        raw_values: list[object | None] = []
        for o in fn.operands[1:]:
            if o.WhichOneof("expr") != "literal":
                raise UnsupportedExpression("'in' values must be literals")
            raw_values.append(_literal_to_python(o.literal))
        return operands[0].is_in(raw_values)
    if op == "coalesce":
        if not operands:
            raise UnsupportedExpression("'coalesce' expects at least 1 operand")
        return pl.coalesce(operands)
    # String ops
    if op == "concat":
        if not operands:
            raise UnsupportedExpression("'concat' expects at least 1 operand")
        return pl.concat_str(operands)
    if op == "lower":
        return operands[0].str.to_lowercase()
    if op == "upper":
        return operands[0].str.to_uppercase()
    if op == "like":
        if len(operands) != 2:
            raise UnsupportedExpression(f"'like' expects 2 operands, got {len(operands)}")
        # Right operand must be a string literal pattern.
        if fn.operands[1].WhichOneof("expr") != "literal":
            raise UnsupportedExpression("'like' pattern must be a literal")
        pattern = fn.operands[1].literal.string_value
        return operands[0].str.contains(_like_to_regex(pattern), literal=False)
    raise UnsupportedExpression(f"operator '{op}' is not implemented")


def _like_to_regex(pattern: str) -> str:
    """Translate SQL LIKE pattern to a regex anchored at both ends."""
    out = ["^"]
    i = 0
    while i < len(pattern):
        ch = pattern[i]
        if ch == "%":
            out.append(".*")
        elif ch == "_":
            out.append(".")
        elif ch in r".^$*+?()[]{}|\\":
            out.append("\\" + ch)
        else:
            out.append(ch)
        i += 1
    out.append("$")
    return "".join(out)


def _polars_dtype(target_type: str) -> pl.DataType:
    t = target_type.lower()
    if t in {"text", "string", "varchar", "char"}:
        return pl.Utf8
    if t in {"int", "integer", "long", "bigint"}:
        return pl.Int64
    if t in {"float", "double"}:
        return pl.Float64
    if t in {"bool", "boolean"}:
        return pl.Boolean
    if t in {"datetime", "timestamp"}:
        return pl.Datetime
    raise UnsupportedExpression(f"cast target_type '{target_type}' not supported")
