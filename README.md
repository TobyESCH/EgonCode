# EgonCode

EgonCode is an open source Java coding agent for the terminal. It runs its own
model and tool loop, keeps local sessions, asks before changing files or running
commands, and can send two coding agents into separate Git worktrees.

## Requirements

- JDK 21 or later to build; Java 21 or later to run
- Git for isolated agent teamwork
- A supported model connection

Build and launch on Linux/macOS:

```sh
./build.sh
./egon.sh --project /path/to/project
```

On Windows, run `build.ps1`, then `egon.cmd --project C:\path\to\project`.
The build uses only the JDK. No Maven or external library is required.

## Connect a model

For ChatGPT Plus plan usage, run `/login`, then `/models`, then
`/model` to browse and choose an available model. After login, EgonCode
selects a recommended available model automatically if the saved model is
missing or unavailable. EgonCode uses OpenAI's documented Sign in with ChatGPT
flow and the public Responses API. Your plan's usage limits are shared with
other participating apps. Sign-in requires a browser and a loopback callback.
Use `/accounts`, `/account <id>`, `/login new`, and `/logout` to manage account
registrations. OAuth tokens are stored in a protected local file.

For a standard OpenAI API key, set `OPENAI_API_KEY`, then run `/provider openai`
and `/model` to pick from your account's models. For Anthropic, set `ANTHROPIC_API_KEY`, then use
`/provider anthropic`. For a local endpoint, set `EGON_LOCAL_URL` to an
OpenAI **Responses API compatible** base URL (ending in `/v1`), and optionally
set `EGON_LOCAL_API_KEY`; then use `/provider local` and choose a model.

Provider changes start a new session so formats and billing routes are never
mixed silently. Anthropic uses API-key billing; Claude subscription credentials
are not used.

## Work in a project

Run `egoncode` in a terminal to open the full-screen dashboard. It shows the
current project, provider, model, mode, session token usage, activity log, and
the brown pixel rabbit from [kanmandev's Bunny animated pixel art](https://kanmandev.itch.io/bunny-animated),
recolored for the dashboard. The rabbit idles, runs during work, and sleeps
after 90 seconds without input. New chats show a welcome screen with an animated
sleeping rabbit; the chat view
shows activity and session details. File edits show a colored diff: additions
are green, removals are red, and hunk headings are purple. Press Ctrl+D or
enter `/diff` to inspect the current Git diff. Press Ctrl+T for a new chat tab, Ctrl+W to
close the active tab. Tab moves to the next chat tab. Shift+Tab cycles
edit → auto → plan → edit. Press Page Up or
Page Down to scroll the log. `/model` opens an arrow-key model picker; if the
ChatGPT account is not connected yet, the picker offers sign-in.
Press Ctrl+O or enter `/open` to open the latest link in your browser, or click the footer's
link in terminals that support hyperlinks. ChatGPT login also tries to open
the sign-in page automatically. Use `egoncode --plain` for the classic line interface. The interactive command
opens the dashboard directly in a Unix terminal, even when `TERM` is reported
as `dumb`. `/model` opens a numbered model list; enter a number or press Enter
for the recommended model.

Rabbit art is © kanmandev and is not covered by this project's MIT license;
see the [asset page](https://kanmandev.itch.io/bunny-animated) for its terms.

Type a request at the prompt. The model can list, search, and read files; read
an available skill; inspect Git status; and, in edit mode, request text edits
or shell commands. `/mode edit` asks before writes and commands. `/mode auto`
accepts project file edits and a small set of common checks such as
`npm run build`, `npm test`, `git status`, and `git diff`. Other shell commands
still ask. `/mode plan` removes write and command tools from the model. The
mode applies to the current run and starts in edit mode next time. The agent stays within the
selected project, and `.env` files are protected from direct file reads.
When approval is needed, the terminal shows a focused panel with the action
and a colored diff. Press 1 or Y to approve once, 2 or N to deny, or use
arrow keys and Enter. Esc denies. Page Up and Page Down scroll the diff.
Optional `~/.egoncode/permissions.json` can set `write` and `command` to
`"ask"` (default), `"allow"`, or `"deny"`. Use `"allow"` only when you trust
all model actions in the selected project.

`/sessions` lists saved sessions and `/resume <id>` loads one. `/compact`
summarizes the current model history; long histories are compacted
automatically. Session files and protected OAuth credentials live in
`~/.egoncode/`. API keys are read from environment variables and are removed
from shell tool subprocess environments.
`/usage` reports provider-reported tokens for the current session, and
`/timeline` shows prompts, model steps, and tool results. For OpenAI and
Anthropic API-key connections, `/usage` also shows the latest rate-limit
remaining counts and reset values returned in response headers. These are API
rate limits, not subscription balances. When Codex is installed and signed in
with the same email as EgonCode's ChatGPT sign-in, EgonCode reads the documented Codex app-server
quota windows and reset times for `/usage` and the sidebar. These values
describe the Codex quota bucket and may differ from an EgonCode app-specific
or workspace limit. For the full plan and app usage, EgonCode links to
[ChatGPT Usage](https://chatgpt.com/settings/usage). The Anthropic connection currently uses
`ANTHROPIC_API_KEY`; Claude subscription usage is in Claude Settings → Usage.

Project instructions can be put in `AGENTS.md` or `EGON.md`. Reusable skills
can be added under `.egoncode/skills/<name>/SKILL.md`, `.agents/skills/`,
`.claude/skills/`, or `~/.egoncode/skills/`. Custom prompt commands live in
`.egoncode/commands/<name>.md` or `~/.egoncode/commands/`; use `$ARGUMENTS`
where the command's arguments should appear.

## Agent teamwork

`/team <goal>` asks a coordinator to split the goal into two tasks. In a Git
project, each agent works in its own worktree under `~/.egoncode/worktrees/`.
Their logs and worktrees remain available for inspection. Run
`/team-status <id>` to see the tasks, and `/team-apply <id> <1|2>` to review,
check, and approve one agent's patch against your working tree. If the project
is not a Git repository, the agents run sequentially in the same directory.

## Commands

`/help`, `/login [new]`, `/logout`, `/accounts`, `/account`, `/models`, `/open`, `/provider`, `/model`, `/mode`, `/diff`, `/status`, `/usage`, `/timeline`,
`/sessions`, `/resume`, `/new`, `/tab-next`, `/tab-prev`, `/tab-close`, `/compact`, `/skills`, `/team`, `/team-status`,
`/team-apply`, `/quit`.

## Current limits

Local endpoints must implement the Responses API. The current tool set includes local files,
shell, Git status, and skills; MCP and language-server tools are not yet wired.
ChatGPT login and provider calls require a real account and have not been
tested against live credentials in this repository. Review commands and diffs
before approving them.

## Testing

```sh
javac -d out src/main/java/dev/egoncode/*.java src/test/java/dev/egoncode/*.java
java -cp out dev.egoncode.SmokeTest
```

The smoke test uses a loopback mock Responses server and does not spend API
usage. See the [OpenAI sign-in documentation](https://developers.openai.com/siwc/token-sharing-open-source)
for the current subscription request contract.

## License

EgonCode's source code is MIT licensed. The rabbit GIFs in
`src/main/resources/` are modified assets from
[Kanmandev's Bunny animated pixel art](https://kanmandev.itch.io/bunny-animated).
They remain under the creator's asset license and are not covered by the MIT
license. That license permits distribution as part of a finished project but
does not permit sharing the assets as a standalone pack.
