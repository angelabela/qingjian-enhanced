# Android AI Chat Assistant — Design

## Goal

Add a dedicated **AI 对话** tool to the Android IME. It complements (and does not replace) the existing AI 情境表达 panel, cloud suggestions, sentence completion, and translation.

The feature has two modes:
1. **智能回复**: generate three distinct, ready-to-send replies from user-provided conversation context.
2. **AI 对话助手**: a multi-turn chat where the user can ask the model to draft, rewrite, explain, or refine a response.

Default output count: 3 reply suggestions. Reply styles should vary naturally (e.g. concise/natural, warm, light/humorous) while staying faithful to the supplied facts and relationship context.

## Context and privacy

Android IMEs cannot reliably or legitimately read arbitrary messages from the host app. Do not attempt accessibility scraping, notification scraping, clipboard polling, or hidden cross-app reads.

Supported context sources:
- Current input field text obtained through the active InputConnection, subject to Android editor privacy flags.
- Text explicitly pasted/entered by the user into the AI panel as conversation context.

Only send context after the user explicitly taps Generate/Send. Never send text automatically while typing. Do not send content for password/private/direct-input editors. Show a clear indication that content will be sent to the configured cloud model. No automatic sending of generated replies; selecting a suggestion inserts it into the current editor.

## Existing infrastructure to reuse

- `CloudConfig.load(context)`: OpenAI-compatible base URL, model, and API key.
- `SecretStore`: existing encrypted API-key storage; never log or persist plaintext key.
- Existing `AiExpressionGenerator`: reference implementation for HTTP requests and JSON response parsing, but keep AI chat as a separate generator and panel.
- Existing `InputPanel`, `PanelKind`, `ToolbarTool`, and `QingjianInputMethodService`: integrate a separate toolbar entry and dedicated panel.
- Existing Android-only workflow `.github/workflows/android-debug.yml`: build only the Android debug APK.

## UX

Add a separate toolbar item named **AI 对话**, with a dedicated icon and panel. Do not rename or remove **AI 情境表达**.

Panel layout:
- Header: AI 对话, model label, close/back.
- Mode switch: 智能回复 / AI 助手.
- Context editor: editable multi-line text; prefill from the current editor only when allowed. Also permit manual paste.
- Reply mode: style chips and three separate selectable response cards. Each card inserts its text into the current input field; it does not send the host app message.
- Assistant mode: conversation transcript, user prompt field, send button, regenerate/continue affordances. Keep conversation in memory for the active panel session only by default.
- Loading, timeout, empty response, network failure, missing cloud configuration, and cancel/retry states.
- Clear context / new conversation action.

## Model protocol

Use the configured OpenAI-compatible `/chat/completions` endpoint. Build requests on a background executor; return UI updates on the main thread. Use bounded timeouts and cap context length. Request structured JSON for reply suggestions (three items with optional style labels); parse defensively and reject empty/oversized results. For assistant mode, preserve a bounded sequence of role/content messages for multi-turn context.

Do not hardcode a provider-specific model name. Use the model configured in Cloud Settings. Do not expose API keys in logs, exceptions, UI, or persistent conversation history.

## Implementation stages

1. Add `AiChatGenerator` with reply-generation and multi-turn chat methods; unit-test response parsing and prompt construction where test infrastructure allows.
2. Add `AI_CHAT` panel kind and toolbar tool, including icon/title mapping and customizable-toolbar support.
3. Add `AiChatPanel` with two modes, explicit context consent, editable context, reply cards, and multi-turn assistant UI.
4. Wire service callbacks using the existing CloudConfig/SecretStore setup. Obtain only current-field text through InputConnection, respect privacy flags, and never auto-submit it.
5. Verify toolbar open/close, generated text insertion, no accidental send, empty/private editor behavior, network errors, and coexistence with existing AI 情境表达.
6. Run Android formatting/tests/build; verify the workflow artifact corresponds to the feature branch commit before sharing an APK.

## Acceptance criteria

- Feature exists only in Android source and Android debug workflow.
- Existing AI 情境表达, cloud prediction, sentence completion, translation, clipboard, and keyboard features remain intact.
- Both modes are accessible from a distinct toolbar item.
- Smart reply returns three usable options from user-provided/current-editor context.
- Assistant mode supports multiple turns within the open session.
- Selecting a result inserts text into the active field without sending it.
- No hidden cross-app context collection; private/direct/password editors are excluded.
- Missing cloud configuration and network/model errors are shown clearly without crashing the IME.
