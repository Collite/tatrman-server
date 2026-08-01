# LLM gateway (LLM Gateway)

LLM gateway is a critical infrastructure service that centralizes and manages access to Large Language Models (OpenAI, Anthropic, etc.) for all other services and agents in the constellation.

## Architecture Overview

This is a **Spring Boot** application written in **Kotlin**. It acts as a smart proxy and control plane.

### Core Stack
*   **Framework**: Spring Boot 4.0.2 (Virtual Threads Enabled)
*   **Language**: Kotlin 2.3
*   **Database**: PostgreSQL (Production) / H2 (Test)
*   **Messaging**: NATS (Event bus)
*   **Security**: OAuth2 Resource Server (Stateless)

### Key Features
1.  **Core Proxy**: Forwards requests to models using the Spring AI Fluent API.
2.  **Dynamic Routing**: Selects models dynamically based on HOCON rules (`rules.conf`).
3.  **Observability**: Logs all prompts and completions to PostgreSQL with Full Text Search (TSVECTOR).
4.  **Async Jobs**: Manages long-running requests via Polling and Webhooks.

## Development

### Building
```bash
just build-kt llm-gateway
```

### Running Locally
```bash
just run-kt llm-gateway
```

## Deployment
The service is deployed to Kubernetes.
```bash
just deploy-kt llm-gateway
```
