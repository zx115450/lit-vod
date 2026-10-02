package com.example.vod.service.commit;

import com.example.vod.common.domain.media.AssetType;

/**
 * 按 {@link AssetType} 分流 commit：VIDEO 走转码、DOCUMENT 走切章等。
 * <p>实现类注册为 Spring Bean，由 {@link CommitStrategyRegistry} 按类型查找。
 */
public interface CommitStrategy {

    /** 本策略负责的资产类型。 */
    AssetType assetType();

    /** 创建任务、更新媒资状态并投递 MQ（或拒绝）。 */
    void commit(CommitContext ctx);
}
