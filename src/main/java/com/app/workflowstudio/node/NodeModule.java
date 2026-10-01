package com.app.workflowstudio.node;

/**
 * 파이프라인 노드 모듈.
 * 새 노드를 추가하려면 이 인터페이스를 구현한 클래스를 {@code @Component}로 등록하면 된다.
 * {@link NodeTypeRegistry}가 자동으로 수집해 에디터 팔레트(/api/node-types)와 실행 엔진에 노출한다.
 */
public interface NodeModule {

    /** 팔레트/설정 패널에 표시할 노드 메타데이터 (id는 전체에서 유일해야 함) */
    NodeTypeInfo info();

    /**
     * 노드 실행. 반환값은 실행 로그의 출력으로 표시되고,
     * 다음 노드에서 {@code ${outputVar}} / {@code ${nodeId.output}} 으로 참조할 수 있다.
     * 출력이 없으면 null 반환. 실패 시 예외를 던지면 파이프라인이 중단된다.
     */
    String execute(NodeContext ctx) throws Exception;
}
