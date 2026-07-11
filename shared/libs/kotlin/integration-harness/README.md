# integration-harness

Shared support for the **integration** test tier (testing arc — see
[`docs/architecture/testing/`](../../../../docs/architecture/testing/)). It is the
kantheon side of the kantheon ↔ olymp split: it **reads** cluster status to assert
a named context is live and **fails fast** if not — it never deploys (bring-up /
teardown is olymp's `infra-up`/`infra-down`, architecture §3).

## What's here

| Piece | Role |
|---|---|
| `@RequiresContext("<name>")` | marks a spec as needing the named context; `<name>` is the one cross-repo contract key (contracts §2) |
| `RequiresContextExtension` | Kotest `BeforeSpecListener` — resolves namespace + asserts readiness before the spec, **fail-fast** |
| `ContextHandle` | injected per spec (`spec.contextHandle()`): resolved namespace + in-cluster service URLs + `wireMockAdmin` |
| `ClusterReader` / `Fabric8ClusterReader` | the **read-only** cluster port (status reads only) |
| `ContextNameRegistrySpec` | component-tier drift guard: every `@RequiresContext` name has an olymp `test-contexts/<name>/` |

WireMock fixtures are loaded at runtime with `WireMockAdmin` (from
`component-testkit`) against `handle.wireMockAdmin` — the same admin protocol the
component tier proved against a container.

## Authoring an integration spec

1. Add the harness to your module's `integrationTest` source set:

   ```kotlin
   dependencies {
       "integrationTestImplementation"(project(":shared:libs:kotlin:integration-harness"))
   }
   ```

2. Write the spec under `src/integrationTest/kotlin`, annotate it, and register
   the gate (Kotest 6 removed `@AutoScan`, so apply it explicitly):

   ```kotlin
   @RequiresContext("query-runquery")
   @ApplyExtension(RequiresContextExtension::class)
   @Tags("integration")
   class RunQueryIntegrationSpec : StringSpec({
       "run_query returns an envelope" {
           val handle = contextHandle()
           // load fixtures into the context's WireMock
           val wm = WireMockAdmin(handle.wireMockAdmin)
           wm.reset()
           wm.importMappingsFromResource("wiremock/query-runquery/<scenario>/mappings.json")
           // drive the service via its resolved in-cluster URL
           val queryMcp = handle.url("ttr-query-mcp")
           // … assertions …
       }
   })
   ```

3. Put fixtures under `src/integrationTest/resources/wiremock/<context>/<scenario>/`.

4. Make sure olymp defines `test-contexts/<name>/` — `ContextNameRegistrySpec`
   (run with `-PolympDir=`) fails the build on a name with no matching context.

## Running

Driven by CI (`infra-up` → `integrationTest` → `infra-down`); the task **skips**
unless a context is named:

```sh
./gradlew :<module>:integrationTest -Pcontext=<name> -Pnamespace=<ns>
```

Without `-Pcontext` it is skipped, so `./gradlew check` and local builds never
need a cluster.
