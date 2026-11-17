# 통합검색 ngram 적용과 API

이 문서는 수동 검토용이다. 인덱스 생성과 환경 설정은 자동 배포나 Flyway에 포함하지 않는다.

## 배포 순서

1. 대상 환경의 DB와 백업을 확인하고 운영 데이터와 분리된 복사본에서 먼저 시험한다. MySQL/PXC 버전, `ngram` 파서, `@@ngram_token_size`, 테이블 크기 및 디스크 여유 공간을 확인한다. 서버 설정을 임의로 변경하지 않는다.
2. [search-ngram-index.sql](search-ngram-index.sql)의 인덱스가 이미 존재하는지 `SHOW INDEX FROM posts`로 확인한다. 동일 컬럼의 FULLTEXT 인덱스가 존재하면 파서까지 확인하고 중복 생성하지 않는다. 기존 인덱스나 Flyway 이력은 수정하지 않는다.
3. 사전 승인된 유지보수 절차에 따라 인덱스를 생성하고 완료·복제 상태·서버 부하를 확인한다. 첫 FULLTEXT 인덱스 생성은 테이블 재구성 및 잠금이 수반될 수 있으므로 실행 계획과 PXC 환경의 영향은 복사본에서 확인한다.
4. 인덱스 존재와 검색 결과를 확인한 후에만 `SEARCH_MYSQL_NGRAM_ENABLED=true`를 해당 환경에 설정한다. 설정은 별도 승인과 GitOps 절차를 따른다. 기본값은 false이다.
5. 장애 시 먼저 설정을 false로 되돌려 LIKE 검색으로 복귀한다. 인덱스 삭제는 별도 검토 대상이다. 운영에서 DB 초기화, 데이터 삭제, 기존 마이그레이션 수정은 하지 않는다.

현재 ngram 분기는 제목·본문 `MATCH ... AGAINST`와 기존 LIKE 검색을 OR로 결합한다. 한 글자 검색은 LIKE만 사용하며, 작성자는 별도 테이블의 이름을 LIKE로 검색한다. ngram 일치는 부분 토큰 때문에 기존 LIKE보다 넓은 결과를 포함할 수 있다. 정확히 일치하는 제목, 작성자, 그 외 결과 순서로 정렬하며 같은 순위는 id 내림차순이다. 점수 실수값을 커서로 사용하지 않아 기존 게시물 커서 계약을 유지한다. LIKE 병행으로 검색 누락을 줄이지만 전체 스캔이 발생할 수 있으므로 ngram 활성화가 곧 성능 향상을 보장하지 않는다. 대상 데이터에서 EXPLAIN과 부하를 측정한다.

`ngram_token_size`는 서버 시작 시 결정되는 설정이며 기본값은 2다. 서버의 토큰 크기와 불용어 설정에 따라 검색 결과가 달라지므로 사전 확인한다. 참고: [MySQL ngram 파서](https://dev.mysql.com/doc/refman/8.4/en/fulltext-search-ngram.html), [MySQL 온라인 DDL](https://dev.mysql.com/doc/refman/8.4/en/innodb-online-ddl-operations.html).

## API와 프론트 연동

`GET /api/v1/search`의 기존 `shortcuts`, `posts`, `nextPostCursor`, `nextPostRank`, `people`, `nextPersonCursor`는 유지한다. `notices`, `nextNoticeCursor`, `nextNoticeRank`를 추가한다. 공지사항은 `posts.post_kind = 'NOTICE'` 중 게시되고 삭제되지 않은 데이터이며 기존 공지 API처럼 차단한 작성자의 글을 제외한다. `NEWS`는 공지 목록에 섞지 않는다.

공지 더보기에는 같은 검색어와 `noticeCursor`, `noticeRank`를 함께 전달한다. 각 섹션의 커서는 독립적이다. 프론트는 응답 필드의 JSON 순서에 의존하지 말고 `shortcuts → notices → posts → people` 순으로 표시한다. 기존 공지 상세 경로는 `/notices/{id}`다. 검색 화면·클릭 처리·더보기 UI는 프론트에서 별도 구현해야 한다.

공지와 게시물의 `summary`는 본문에서 검색어 전체가 처음 등장한 위치 주변 최대 200자다. 잘린 앞뒤에는 `…`을 붙인다. 본문에 검색어 전체가 없는 제목·작성자 일치나 ngram 부분 일치의 경우 본문 앞부분을 반환한다. 강조 표시를 위한 HTML은 삽입하지 않으며, 기존 `body` 응답은 그대로 유지한다.

로컬 통합 테스트의 `UnifiedSearchMySqlNgramTest`는 `RUN_LOCAL_SEARCH_MYSQL_TEST=true`일 때만 127.0.0.1:13319의 `skku_search_test`에 접속한다. 별도의 임시 MySQL과 가상 테이블·데이터를 준비한 경우에만 실행하며 개발·운영 DB에는 사용하지 않는다.

인덱스가 없는 상태에서 설정이 켜져 MySQL 오류 1191이 발생하면 서버는 해당 검색을 LIKE로 다시 실행하고 경고 로그를 남긴다. 이는 설정 실수에 대한 복구이며 인덱스 검증과 부하 시험을 대체하지 않는다. 인덱스 준비 후 대상 환경의 GitOps 설정에 `SEARCH_MYSQL_NGRAM_ENABLED=true`를 추가하고 배포를 반영한다. 비활성화하려면 `false`로 되돌린다.
