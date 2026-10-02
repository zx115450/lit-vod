package com.example.vod.service.commit;

import com.example.vod.common.domain.media.AssetType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Commit 策略注册表：Spring 注入全部 {@link CommitStrategy}，按 {@link AssetType} 查找。
 */
@Component
public class CommitStrategyRegistry {

    private final Map<AssetType, CommitStrategy> strategies = new EnumMap<>(AssetType.class);

    public CommitStrategyRegistry(List<CommitStrategy> strategyList) {
        for (CommitStrategy strategy : strategyList) {
            CommitStrategy prev = strategies.put(strategy.assetType(), strategy);
            if (prev != null) {
                throw new IllegalStateException("duplicate CommitStrategy for " + strategy.assetType()
                        + ": " + prev.getClass().getSimpleName()
                        + " vs " + strategy.getClass().getSimpleName());
            }
        }
    }

    public CommitStrategy get(AssetType assetType) {
        AssetType type = assetType != null ? assetType : AssetType.VIDEO;
        CommitStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "commit not implemented for assetType: " + type);
        }
        return strategy;
    }
}
