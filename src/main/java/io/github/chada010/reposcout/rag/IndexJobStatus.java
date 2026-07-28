package io.github.chada010.reposcout.rag;

/** 索引任务状态，状态流转为 QUEUED -> RUNNING -> SUCCEEDED/FAILED。 */
public enum IndexJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
