module: nlp-mcp
image: ghcr.io/boraperusic/nlp-mcp   # jib
ports: { http: 7272 }
needs:
  downstream: [ nlp ]
wave: 2    # query-path — MCP edge over the NLP foundation
