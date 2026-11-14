Edit an existing user-attached image.

Returns `filePath` on success.
- If editing succeeds: embed the image in your final reply using Markdown image syntax `![description](filePath)` so that it is displayed to the user in the chat.
- If editing fails or the tool returns an error: tell the user clearly that image editing failed and invite them to try again. NEVER claim that the photo is ready, generated, or shown above when the tool fails or when no valid image file was created.
