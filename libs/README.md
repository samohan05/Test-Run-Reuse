# libs/

`pom.xml` compiles this module against **system-scoped** jars that live here. These are
proprietary Codebeamer / vendor libraries that are not published to Maven Central, so they
are **not** committed to this repository (see `.gitignore`). Copy them from your own
Codebeamer 3.2.0.2 installation before running `mvn package`:

| Jar | Typical source location |
|---|---|
| `cb-3.2.0.2.jar` | `<CB_INSTALL>/tomcat/webapps/cb/WEB-INF/lib/` |
| `cb-common-3.2.0.2.jar` | same |
| `cb-jsp-wiki-3.2.0.2.jar` | same |
| `spring-beans-6.2.17.jar`, `spring-context-*`, `spring-core-*`, `spring-aop-*`, `spring-expression-*`, `spring-jcl-*`, `spring-tx-*`, `spring-web-*` | same |
| `jakarta.servlet-api.jar` | same |
| `mybatis-spring-3.0.3.jar`, `mybatis-3.5.19.2.jar` | same — `TrackerManager` (in `cb.jar`) extends `SqlSessionDaoSupport`, so these are needed to compile even though streamreuse's own sources never reference MyBatis directly |
| `log4j-api-2.25.0.jar` | same |
| `jackson-databind-2.19.2.jar`, `jackson-core-*`, `jackson-annotations-*` | same |
| `velocity-engine-core-2.3.jar` | same |

If your site runs a different Codebeamer 3.2.0.2 patch level, match the exact jar versions
already present under `WEB-INF/lib` on your server rather than the versions pinned above -
`pom.xml`'s `<cb.version>` and the Spring/Jackson/log4j/MyBatis version properties exist
precisely so you can adjust them to match.

**Compiler note:** these vendor jars are themselves compiled for a specific JDK (class file
version). Build with a JDK new enough to *read* those class files even though `pom.xml`
targets bytecode 11 for output — e.g. `cb-3.2.0.2.jar` requires a JDK 21 `javac` to compile
against; an older JDK fails with `cannot access ... bad class file ... wrong version`.
