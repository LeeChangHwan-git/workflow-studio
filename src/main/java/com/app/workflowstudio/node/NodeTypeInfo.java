package com.app.workflowstudio.node;

import java.util.List;

/**
 * 노드 타입 메타데이터 — 프론트엔드 types.ts 의 NodeTypeInfo 와 1:1 대응.
 *
 * @param category 팔레트 그룹명 (null 이면 팔레트에 노출하지 않음)
 * @param trigger  트리거 노드 여부 — 파이프라인의 시작점. 입력 연결을 받지 않으며,
 *                 외부 이벤트(웹훅 등)로 실행되면 이 노드에서 도달 가능한 노드만 실행된다.
 * @param sockets  아래쪽 소켓 목록 (부품 노드를 끼우는 자리, 없으면 빈 목록)
 * @param provides 부품 노드라면 제공하는 부품 종류 (예: ai_model). 부품 노드는 단독 실행되지 않고
 *                 소켓에 끼워진 노드가 설정값을 읽어 사용한다. 일반 노드는 null
 */
public record NodeTypeInfo(
        String id,
        String label,
        String icon,
        String color,
        String category,
        List<NodeParam> params,
        boolean trigger,
        List<NodeSocket> sockets,
        String provides
) {
    /** 일반(비트리거) 노드 */
    public NodeTypeInfo(String id, String label, String icon, String color, String category, List<NodeParam> params) {
        this(id, label, icon, color, category, params, false, List.of(), null);
    }

    /** 트리거 여부 지정 */
    public NodeTypeInfo(String id, String label, String icon, String color, String category, List<NodeParam> params,
                        boolean trigger) {
        this(id, label, icon, color, category, params, trigger, List.of(), null);
    }

    /** 소켓을 가진 노드 (예: AI 에이전트) */
    public static NodeTypeInfo withSockets(String id, String label, String icon, String color, String category,
                                           List<NodeParam> params, List<NodeSocket> sockets) {
        return new NodeTypeInfo(id, label, icon, color, category, params, false, sockets, null);
    }

    /** 부품 노드 (소켓에 끼우는 노드) */
    public static NodeTypeInfo part(String id, String label, String icon, String color, String category,
                                    List<NodeParam> params, String provides) {
        return new NodeTypeInfo(id, label, icon, color, category, params, false, List.of(), provides);
    }

    public boolean isPart() {
        return provides != null;
    }
}
