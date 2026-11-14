Generate a new image from a text description.

Returns `filePath` on success.
- If generation succeeds: embed the image in your final reply using Markdown image syntax `![description](filePath)` so that it is displayed to the user in the chat. CRITICAL: Once `generate_image` succeeds and returns a `filePath`, image generation is 100% COMPLETE. NEVER call `generate_image` again in the same turn. Conclude immediately by presenting the generated image.
- If generation fails or the tool returns an error: tell the user clearly that image generation failed and invite them to try again (e.g. "Photo nahi ban payi, dobara try karo"). NEVER claim that the photo is ready, generated, or shown above when the tool fails or when no valid image file was created.
