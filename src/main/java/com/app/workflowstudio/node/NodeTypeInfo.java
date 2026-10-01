package com.app.workflowstudio.node;

import java.util.List;

/**
 * 노드 타입 메타데이터 — 프론트엔드 types.ts 의 NodeTypeInfo 와 1:1 대응.
 *
 * @param category 팔레트 그룹명 (null 이면 팔레트에 노출하지 않음)
 * @param trigger  트리거 노드 여부 — 파이프라인의 시작점. 입력 연결을 받지 않으며,
 *                 외부 이벤트(웹훅 등)로 실행되면 이 노드에서 도달 가능한 노드만 실행된다.
 */
public record NodeTypeInfo(
        String id,
        String label,
        String icon,
        String color,
        String category,
        List<NodeParam> params,
        boolean trigger
) {
    /** 일반(비트리거) 노드 */
    public NodeTypeInfo(String id, String label, String icon, String color, String category, List<NodeParam> params) {
        this(id, label, icon, color, category, params, false);
    }
}
