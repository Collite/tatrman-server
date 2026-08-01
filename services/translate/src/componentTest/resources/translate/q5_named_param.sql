SELECT "date_dim"."d_year", SUM("store_sales"."ss_sales_price") AS "total_sales"
FROM "store_sales"
    INNER JOIN "date_dim" ON "store_sales"."ss_sold_date_sk" = "date_dim"."d_date_sk"
WHERE "date_dim"."d_year" = ?
GROUP BY "date_dim"."d_year"
