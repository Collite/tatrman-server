module: echo-mcp
image: ghcr.io/boraperusic/echo-mcp   # jib
ports: { http: 7267 }
needs:
  downstream: [ echo ]
wave: 2    # query-path — MCP edge over the Czech-aware fuzzy matcher
