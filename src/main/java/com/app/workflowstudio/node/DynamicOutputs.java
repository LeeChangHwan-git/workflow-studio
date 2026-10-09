package com.app.workflowstudio.node;

import com.app.workflowstudio.model.PipelineNode;

import java.util.Map;

/** 출력이 노드 설정에 따라 정해지는 모듈 (예: 분기 규칙) — 출력 id → 이름 */
public interface DynamicOutputs {
    Map<String, String> outputLabels(PipelineNode node);
}
