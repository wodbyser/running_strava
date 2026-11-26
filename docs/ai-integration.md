# In-app AI answer (LangChain4j + OpenRouter)

The prompt pages (`/ai`, `/next-training/prompt`, `/rate-training/prompt`, `/compare/prompt`,
`/compare-runs/prompt`) build a large prompt in the app. Until now the only way to use it was to copy it into an
external chatbot. This feature adds a second card on the same page that sends the existing prompt to a configured
LLM and shows the answer next to it. The prompt text itself is unchanged.

## Chosen provider and model

**OpenRouter** (`https://openrouter.ai/api/v1`) with the free model **`particle-ai/particle-1:free`** by default.

- OpenRouter's free tier has no mandatory cost and no per-request token cap.
- The API is OpenAI-compatible, so LangChain4j's OpenAI client covers it.
- OpenRouter does not log prompts/completions by default, and only routes to providers that train on data if you
  opt in.
- Free models are explicitly "not suitable for production". That is acceptable here: this is a personal app with
  low volume, and the feature is optional.

Everything is configuration; the code has no provider-specific branch. Switching to another OpenAI-compatible
provider means setting `AI_BASE_URL`, `AI_MODEL`, `AI_PROVIDER_NAME` and `AI_API_KEY`.

## Getting a free OpenRouter key

1. Go to <https://openrouter.ai/> and sign in.
2. Open **Keys** and create a key.
3. No card is needed. Copy the key once; it is shown only at creation.

Put it in the environment (see below). Never commit it.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `AI_API_KEY` | *(blank)* | Provider key. **Blank = feature off**: no outbound call, no AI button, the prompt page is unchanged. |
| `AI_ENABLED` | `true` | Set to `false` to switch the feature off even when a key is present. |
| `AI_BASE_URL` | `https://openrouter.ai/api/v1` | OpenAI-compatible endpoint. |
| `AI_MODEL` | `particle-ai/particle-1:free` | Model ID as the provider expects it. |
| `AI_PROVIDER_NAME` | `OpenRouter` | Label shown in the page, e.g. `Model: … via OpenRouter`. |

Where to put them:

- **On the server**: `deploy/.env` (the deploy compose file reads that file).
- **Local `./gradlew :runner:bootRun`**: the repo-root `.env`; the Gradle convention plugin loads it automatically.

The other limits have sensible defaults in `runner/src/main/resources/application.yml` and normally do not need
changing:

```yaml
app.ai:
  temperature: 0.3
  max-output-tokens: 4000
  timeout: 90s
  max-prompt-chars: 60000
  max-requests-per-hour: 10
```

## Changing model or provider

The free catalogue changes. Check the current models with:

```bash
curl -s https://openrouter.ai/api/v1/models | jq '.data[].id' | grep ':free'
```

Then change only `AI_MODEL` (and restart). Never hardcode a model list in a file; the app sends whatever
`AI_MODEL` says.

| Provider | `AI_BASE_URL` | Notes |
|---|---|---|
| OpenRouter | `https://openrouter.ai/api/v1` | Default. Free models end in `:free`. |
| Groq | `https://api.groq.com/openai/v1` | 30 req/min, 1,000 req/day, but **8,000 tokens/min** is also a per-request ceiling. The larger prompts are rejected (HTTP 413). Only for short prompts. |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai/` | Generous free limits, but the Gemini terms effective 23 Mar 2026 say API clients for users in the EEA, Switzerland or the UK may only use **Paid Services**. This app is deployed from Belgium, so use a paid key if you choose Gemini. |
| Mistral | `https://api.mistral.ai/v1` | ~1 req/s and ~500k tokens/min, but free API access is uncertain since Nov 2026 and data may be used for training. Not recommended. |
| Ollama / LM Studio | `http://localhost:11434/v1` | Local and free; set `AI_API_KEY=local` (the client requires a non-blank value). |

Excluded providers (paid trial only, token caps, credits, non-commercial terms): Cerebras, GitHub Models,
Together, HuggingFace, Cohere.

## Free-tier limits (OpenRouter)

- **20 requests per minute** and **50 requests per day** for accounts that have bought less than $10 in credits.
- **1,000 requests per day** after a one-time purchase of at least $10 in credits. That purchase is optional and
  is never required for this feature.
- Failed requests can count towards the quota.
- Token limits and the list of `:free` models change without notice.
- A free model also shares an upstream pool with everyone else. A popular model such as
  `google/gemma-4-31b-it:free` can return 429 for hours while another `:free` model answers fine, even when your own
  daily quota is almost untouched. If the page says the free limit is reached but `/api/v1/key` shows plenty of
  `free_model_daily_requests` left, switch `AI_MODEL` to another `:free` model instead of waiting.
- The shipped default is a pragmatic pick that was verified end-to-end, not a guarantee. Change it whenever the
  catalogue shifts.

## Cost traps

- Nothing is billed for a model ID that ends in `:free`.
- A model ID **without** `:free` can bill your account. Keep `AI_MODEL` on a `:free` model unless you deliberately
  want to pay.
- Buying credits is optional. The default configuration never requires it.
- The app itself never charges anything; only the provider account can.

## Privacy

- The app does not store prompts or answers: no database row, no file, no in-memory history. The answer is rendered
  in the page and disappears on reload.
- The app does not log prompts or answers. On a failure it logs only the failure kind and the Java exception class.
- The API key is server-side only. It never appears in HTML, JSON, JavaScript, logs or error messages.
- OpenRouter does not log prompts/completions by default (you can check your account's privacy setting).
- **Recovery prompts contain health data** (HRV, resting heart rate, sleep, Body Battery, stress). The page shows an
  extra callout when that data is included. Sending it is a deliberate click by the user, never automatic.

## Timeouts, retries and limits

- One call per click, no streaming, no background job.
- `maxRetries = 0`: a 429 or 5xx fails fast instead of hanging. The user can click again.
- `app.ai.timeout` (90 s default) bounds the request.
- Per browser session: at most `max-requests-per-hour` (10) calls in a rolling hour, and only one call in flight.
- A prompt longer than `max-prompt-chars` (60,000) is refused before any network call; the page tells the user to
  copy it to an external chatbot instead.

## Endpoint

`POST /ai/answer`, `application/x-www-form-urlencoded`, field `prompt`. The response is JSON:

| Status | Body | When |
|---|---|---|
| 200 | `{"answer": "...", "model": "...", "provider": "OpenRouter"}` | Success |
| 400 | `{"error": "De prompt is leeg."}` | Blank prompt |
| 400 | `{"error": "Deze prompt is te groot om te versturen. …"}` | Longer than `max-prompt-chars` |
| 429 | `{"error": "..."}` | Local hour limit, an in-flight call, or the provider's rate limit |
| 502 | `{"error": "..."}` | Rejected key, unknown model, empty answer, other provider error |
| 503 | `{"error": "AI is niet ingesteld op deze installatie."}` | No key / feature off |
| 504 | `{"error": "..."}` | Timeout |

The model and base URL are never accepted from the client. There is no GET variant.

## Troubleshooting

- **401 / 403**: the key is wrong, revoked or lacks access. Create a new key and restart.
- **429**: the free quota is used up, or several requests overlap. Wait, or copy the prompt to an external chatbot.
- **404**: the model ID disappeared from the catalogue. Pick another `:free` model and update `AI_MODEL`.
- **400 / 413**: the prompt is larger than the provider accepts (Groq's 8,000 TPM is the usual cause). Use
  OpenRouter's `:free` models, which have much larger context windows.
- **504**: raise `app.ai.timeout`, or retry later.
- **"Het model gaf een leeg antwoord"**: some free models are reasoning models that spend the whole
  `max-output-tokens` budget on reasoning and return no text. Pick a non-reasoning `:free` model, or raise
  `app.ai.max-output-tokens`.
- **Button missing**: `AI_API_KEY` is blank or `AI_ENABLED=false`. The page then shows
  "AI is niet ingesteld." and everything else keeps working.

## Verify manually

1. Without a key: start the dev profile and open `/ai`. The page looks as before plus the
   "AI is niet ingesteld" note; copy and download still work.
2. With a key: open `/ai`, click **Vraag het aan de AI**. You should see the spinner, an elapsed counter, then the
   Dutch answer, the model line, and a **Kopieer antwoord** button that works independently of **Kopieer prompt**.
3. Click again while a call is running: nothing extra is sent.
4. Temporarily use a wrong key: the card shows a clear Dutch error and the page source contains no key.
5. Check `/next-training/prompt`, `/rate-training/prompt`, `/compare/prompt` and `/compare-runs/prompt`: the same
   card appears because they share `templates/ai.html`.
