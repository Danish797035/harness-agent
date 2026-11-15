Generate a new image from a text description.

- **Direct visual prompt triggers**: If the user's message is an image generation prompt, a detailed visual scene description, portrait/art prompt, photography description (e.g., contains phrases like "cinematic portrait", "ultra-realistic", "hyper-detailed", "photo of", aspect ratio "AR 4:5", camera/lighting specs), or image generation prompt without explicit command verbs like "banao" or "generate", you MUST immediately invoke `generate_image` using the entire description as the `prompt` parameter. Do NOT critique, analyze, advise, explain, or answer conversationally instead of generating.
- Returns `filePath` on success.
- If generation succeeds: embed the image in your final reply using Markdown image syntax `![description](filePath)` so that it is displayed to the user in the chat.
- If generation fails or the tool returns an error: tell the user clearly that image generation failed and invite them to try again (e.g. "Photo nahi ban payi, dobara try karo"). NEVER claim that the photo is ready, generated, or shown above when the tool fails or when no valid image file was created.
