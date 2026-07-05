module: steropes
image: ghcr.io/boraperusic/steropes     # build-py (Python image; not Jib)
ports: { http: 7300, grpc: 7301 }
needs:
  pg-database: null
  seaweed-bucket: null
  keycloak: null
  downstream: []
wave: 2    # worker (the Kyklop dispatcher calls it; services/workers that agents' path uses)
externally-exposed: null
