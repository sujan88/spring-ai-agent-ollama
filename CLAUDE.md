# spring-ai-agent-ollama: project guide

Spring Boot 4.1, Spring AI 2.0, Java 17 (keep code Java 17 compatible: no Java 21 APIs such as List.getFirst() or switch pattern matching; Lucene stays on 9.x). Postgres (pgvector) holds vectors, chat memory and
pending actions. Profiles: `local` (default, Ollama) and `cloud` (OpenAI). See README.md.

## Commands
- `./mvnw test`: unit tests (no network, no Docker)
- `./mvnw verify`: plus RagIT (Testcontainers)
- `./mvnw verify -Pevals`: plus EvalIT (needs OPENAI_API_KEY; costs money)
- `./mvnw spring-boot:run`: runs with compose.yaml (Postgres + Ollama)

## Adding a @Tool (checklist)
1. Put it in a `@Component` under `tools/`; register the object in `AiConfig#supportChatClient`.
2. `ToolContext toolContext` is the **last** parameter. Read identity with
   `ToolContextKeys.require(toolContext, ToolContextKeys.CUSTOMER_ID)`, never from an argument.
3. Validate arguments and return a record with a machine-readable message
   (`NOT_FOUND: ...`, `INPUT_REQUIRED: ...`) instead of throwing for expected cases.
4. Do date maths and business rules in Java and return facts (`cancellable`, `returnWindowOpen`).
5. Anything irreversible: call `PendingActionService.prepare(...)` and add a `PendingActionExecutor`
   that re-checks ownership and state. Never change data directly from a tool.
6. A new backend call goes on the port interface (`OrderGateway`) and into every adapter.
7. Tell the model when to use it in `prompts/system.st`.
8. Add a unit test like `OrderToolsTest` (ownership, bad input, the happy path).

## Adding knowledge
Drop a `.md` file into `src/main/resources/docs/kb/`. Use `## ` headings; sections are never split.
Add golden questions for it in `src/test/resources/rag-eval/golden-questions.json`; `GoldenSetTest`
checks that every expected phrase sits inside one real chunk.

## Rules
- No secrets in files. Keys come from environment variables (`.env.example`).
- New advisors implement both `CallAdvisor` and `StreamAdvisor`, or they are skipped on `/stream`.
- Advisor order: guardrail (HIGHEST) < memory (+200) < token audit (+250) < ToolCallingAdvisor (+300).
- Keep the MCP server off outside the local profile until it has authentication.
