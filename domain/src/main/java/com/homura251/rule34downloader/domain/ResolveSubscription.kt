package com.homura251.rule34downloader.domain

import com.homura251.rule34downloader.domain.input.PostUrlParser

data class ResolvedSubscription(val sourcePostId: Long, val candidates: List<Rule34Tag>)

class ResolveSubscription(private val catalog: CatalogGateway) {
    suspend operator fun invoke(input: String): ResolvedSubscription {
        if (PostUrlParser.parsePoolId(input) != null) {
            throw SourceFailure(FailureKind.BAD_RESPONSE, "纯 API 模式暂不支持新增 Pool；已有图集仍可离线查看。")
        }
        val postId = PostUrlParser.parsePostId(input)
        if (postId != null) {
            val post = catalog.getPost(postId)
            return ResolvedSubscription(post.id, catalog.resolveArtistTags(post))
        }
        val tag = PostUrlParser.parseArtistTag(input)
            ?: throw SourceFailure(FailureKind.BAD_RESPONSE, "请输入单个作者标签，或 Rule34 帖子链接 / ID。")
        return ResolvedSubscription(0, listOf(Rule34Tag(tag, 1, 0)))
    }
}
