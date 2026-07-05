module: kadmos-mcp
image: ghcr.io/boraperusic/kadmos-mcp   # jib
ports: { http: 7272 }
needs:
  downstream: [ kadmos ]
wave: 2    # query-path — MCP edge over the NLP foundation
