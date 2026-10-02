package com.example.vod.service.commit;

import com.example.vod.common.domain.media.AssetType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * CHAPTER 禁止直传 commit，只能由切章 Worker 写回。
 */
@Component
public class ChapterCommitStrategy implements CommitStrategy {

    @Override
    public AssetType assetType() {
        return AssetType.CHAPTER;
    }

    @Override
    public void commit(CommitContext ctx) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "CHAPTER cannot be committed via upload; produced by document split worker");
    }
}
