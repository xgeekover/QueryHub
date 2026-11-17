package com.queryhub.application.runtime;

/** 입력 포트: 런타임 상태(이름, 버전, 모드, 기동 시각, 가동 시간)를 조회한다. */
public interface GetRuntimeInfoUseCase {

  RuntimeInfo getRuntimeInfo();
}
