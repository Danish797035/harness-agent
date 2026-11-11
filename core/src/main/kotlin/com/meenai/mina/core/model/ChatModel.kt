package com.meenai.mina.core.model

interface ChatModel {
    val capabilities: Capabilities

    /** 模型名,只用于记录。 */
    val name: String get() = ""

    /** 失败时抛 [ModelError]。 */
    suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply
}

/** 支持逐段文字的模型；不支持的实现继续走 [ChatModel.complete]。 */
interface StreamingChatModel : ChatModel {
    /** [onText] 收到的是本次请求已生成的可见文字全文；不包含模型私有思考或工具参数。 */
    suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply

    /**
     * 收到任何有效流事件(文字、思考、工具调用等)时通知 [onActivity]。
     * 默认实现将任何文字回调视作活动。
     */
    suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
        onActivity: () -> Unit,
    ): ModelReply = completeStreaming(system, messages, tools) { chunk ->
        onActivity()
        onText(chunk)
    }
}
