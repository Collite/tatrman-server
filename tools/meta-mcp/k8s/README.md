module: veles-mcp
image: ghcr.io/boraperusic/veles-mcp   # jib
ports: { http: 7262 }
needs:
  downstream: [ veles ]
wave: 1    # registry/core — MCP edge over the metadata / model-graph service
