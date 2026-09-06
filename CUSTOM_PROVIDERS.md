# Custom OpenAI-Compatible Provider Support

This feature allows you to use alternative AI providers that are compatible with the OpenAI API standard, such as OpenRouter, TogetherAI, Perplexity, and others.

## How to Use

### 1. Enable Custom Provider Mode

Set the system property when launching the game:
```
-Daiplayer.llmMode=custom
```

### 2. Configure API Settings

1. Open the in-game API Keys configuration screen
2. Set the following fields:
   - **Custom API URL**: The base URL of your provider (e.g., `https://openrouter.ai/api/v1`)
   - **Custom API Key**: Your API key for the provider

AI-Player uses Chat Completions first for a base URL and automatically retries the Responses API when the provider or selected model does not support Chat Completions. To prefer the Responses API immediately, enter the full endpoint (for example, `https://api.openai.com/v1/responses`).

### 3. Select a Model

The system will automatically fetch available models from your provider's `/models` endpoint and display them in the model selection interface.

## Supported Providers

Any provider that implements the OpenAI API standard should work. Some examples:

- **OpenRouter**: `https://openrouter.ai/api/v1`
- **TogetherAI**: `https://api.together.xyz/v1`
- **Perplexity**: `https://api.perplexity.ai/`
- **Groq**: `https://api.groq.com/openai/v1`
- **Local LM Studio**: `http://localhost:1234/v1`

## API Compatibility

The custom provider implementation uses the following OpenAI API endpoints:

- `GET /models` - For fetching available models
- `POST /chat/completions` - For Chat Completions requests
- `POST /responses` - For Responses API requests

The provider must implement `GET /models` and at least one of the two generation endpoints using the corresponding OpenAI request and response format. AI-Player automatically falls back between the generation endpoints for endpoint/model compatibility errors and remembers the working endpoint for that model.

## Troubleshooting

- **"Custom provider selected but no API URL configured"**: Make sure you've set the Custom API URL field
- **"Custom API key not set in config!"**: Make sure you've set the Custom API Key field
- **Empty model list**: Check that your API key is valid and the URL is correct
- **Connection errors**: Verify that the provider URL is accessible and supports the OpenAI API format