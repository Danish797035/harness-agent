Edit an existing user-attached image.

Returns `filePath` on success.
- If editing succeeds: embed the image in your final reply using Markdown image syntax `![description](filePath)` so that it is displayed to the user in the chat. CRITICAL: Once `edit_image` succeeds and returns a `filePath`, image editing is 100% COMPLETE. NEVER call `edit_image` again in the same turn. Conclude immediately by presenting the edited image.
- If editing fails or the tool returns an error: tell the user clearly that image editing failed and invite them to try again. NEVER claim that the photo is ready, generated, or shown above when the tool fails or when no valid image file was created.
