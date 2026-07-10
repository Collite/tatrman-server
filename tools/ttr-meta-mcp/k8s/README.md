module: ariadne-mcp
image: ghcr.io/boraperusic/ariadne-mcp   # jib
ports: { http: 7262 }
needs:
  downstream: [ ariadne ]
wave: 1    # registry/core — MCP edge over the metadata / model-graph service
