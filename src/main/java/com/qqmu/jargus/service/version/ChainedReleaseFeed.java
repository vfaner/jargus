package com.qqmu.jargus.service.version;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * GitHub 为主、Gitee 兜底的发布源。
 *
 * <p>GitHub 不可达/限流/响应异常时立即改查 Gitee；两者都失败才向外抛异常。
 * 不静默吞掉单源失败：页面仍能通过 `source` 标注当前主栏来自哪个平台。
 */
@Component
@Primary
@Slf4j
public class ChainedReleaseFeed implements ReleaseFeed {

    private final ReleaseFeed github;
    private final ReleaseFeed gitee;

    @Autowired
    public ChainedReleaseFeed(GithubReleaseFeed github, GiteeReleaseFeed gitee) {
        this.github = github;
        this.gitee = gitee;
    }

    /** 测试缝：直接注入两个内存 feed。 */
    ChainedReleaseFeed(ReleaseFeed github, ReleaseFeed gitee) {
        this.github = github;
        this.gitee = gitee;
    }

    @Override
    public Release latest() throws Exception {
        return fetch(ReleaseFeed::latest);
    }

    @Override
    public Release byTag(String tag) throws Exception {
        return fetch(feed -> feed.byTag(tag));
    }

    private Release fetch(FeedCall call) throws Exception {
        try {
            return call.get(github);
        } catch (Exception githubFailure) {
            log.debug("GitHub 发布信息不可用，改用 Gitee: {}", githubFailure.toString());
            try {
                return call.get(gitee);
            } catch (Exception giteeFailure) {
                IllegalStateException failure =
                        new IllegalStateException("GitHub 与 Gitee 发布信息均不可用", giteeFailure);
                failure.addSuppressed(githubFailure);
                throw failure;
            }
        }
    }

    @FunctionalInterface
    private interface FeedCall {
        Release get(ReleaseFeed feed) throws Exception;
    }
}
