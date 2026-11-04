# Spring 1. Boot, configuration, profiles and beans

> How Spring Boot assembles the application, how TaskFlow configures it so a mistake fails at startup instead of at 3am, and how beans and proxies behave. *Seen* = observed in this project · *verified* = read in the source · *inferred* = reasoned, not run. Sources: [../SOURCES.md](../SOURCES.md).

## Auto-configuration is conditional, not magic

- Boot's auto-configurations are classes listed in `AutoConfiguration.imports`, each guarded by `@Conditional…` annotations. `@ConditionalOnMissingBean` is why **your own `@Bean` wins**: declare one and Boot's backs off.
- **Declaring your beans switches Boot's defaults off** (*verified*): any `SecurityFilterChain` removes the default chain; a `UserDetailsService`, `AuthenticationProvider` or `AuthenticationManager` removes the generated user and password; Boot creates a `JwtDecoder` only from `spring.security.oauth2.resourceserver.jwt.*` properties. *What happened to us (Phase 1):* once our own user service existed, the test credentials in `application-test.yml` silently stopped working.
- **Seeing why:** `--debug` prints the condition report. With Maven: `./mvnw spring-boot:run -Dspring-boot.run.arguments=--debug` (`./mvnw spring-boot:run --debug` turns on *Maven's* debug output instead). Reading the report is still owed from Phase 0.
- **`@SpringBootApplication` scans from its own package downward.** Move the main class and beans silently vanish.

## Package by feature; `common` never imports a feature

Packages are features (`user`, `organization`, `organization.invitation`, `project`) plus `common` for what every feature uses. **`common` never imports a feature**; when shared code needs a feature's answer, `common` declares an **interface** the feature implements:
- `ErrorCode` (common) → `OrganizationErrorCode`, `UserErrorCode`, … (Phase 0).
- `SessionStatus` (common/security) → `JpaSessionStatus` (user.session) (Phase 2).
- `MembershipLookup` (common/tenant) → `JpaMembershipLookup` (organization) (Phase 3).

*What happened to us:* the rule broke twice in Phase 1 (`TokenProperties` importing `TokenPurpose`; the security config importing `TaskflowUserDetailsService`), and `UserRole` moved to `common/security` in Phase 2 because the JWT converter parses roles. Feature → feature imports are allowed (`organization` uses `user.Reauthentication`).

## Profiles

- **`dev`, `test`, `prod`.** `application.yml` holds what's identical everywhere; profile files **merge** over it (the base always loads, the profile overrides keys).
- **Environment variables sit above YAML** in the property-source order, and relaxed binding maps `SPRING_DATASOURCE_URL` → `spring.datasource.url`, `default-page-size` → `defaultPageSize`. That's what makes 12-factor config work.
- **`application.yml` sets `spring.profiles.active: dev`** as a default, so tests without `@ActiveProfiles` run as `dev` (*seen*: three of four contexts logged it), and a prod deployment that forgets `SPRING_PROFILES_ACTIVE` would run as `dev`. Choosing `dev` in the IDE run configuration instead would make a missing profile hit the fail-closed branch (raised 2026-10-03, not decided).
- **`@Profile("dev")` works only on a bean:** the class also needs `@Component`.
- **No Hibernate SQL logging in `prod`;** `show-sql=false` everywhere (it writes to stdout, bypassing the logging config and JSON logs).

## Typed configuration that fails at startup

- **One `@ConfigurationProperties` record per area** (`ApiProperties`, `JwtProperties`, `LockoutProperties`, `SessionProperties`, `TokenProperties`, `CorsProperties`, `FrontendProperties`, invitations in §3.3), **constructor-bound** (plain classes with private fields and no setters bind nothing), `@Validated`, with Bean Validation on fields.
- **Registered by name** in `TaskflowApplication`'s `@EnableConfigurationProperties({...})` list, not scanned: a missing entry fails startup with "no qualifying bean" (§3.3 brief).
- **Cross-field rules go in the record's compact constructor** (`defaultPageSize ≤ maxPageSize`; "all three key fields or none"). *What happened to us (Phase 1):* the compact constructor called the **accessor** (`emailVerificationTtl()`), which is still `null` there, and crashed every startup. Validate the **parameter**.
- *Seen (Phase 0 deliberate failures):* `max-page-size: 0` → startup fails naming the property, value and constraint; `default-page-size > max-page-size` → fails from the compact constructor. That failure is the whole argument for typed config over `@Value`.
- **Redact secrets in `toString()`:** records print every component (properties, the signing key, issued tokens).

## Secrets, and failing closed

- **No defaults for secrets:** `${DB_PASSWORD}`, never `${DB_PASSWORD:dev-value}`. *What happened to us (Phase 0):* colon-defaults were added to every variable, so a prod deploy with no config would have started against localhost with the committed dev password. *Seen:* `prod` with no env → startup fails on the unresolved placeholder.
- **Secrets from environment variables;** `.env` gitignored, `.env.example` committed, and the ignore rule added **before** the secret file existed (once committed, it's in history forever). Config named for the consumer (`DB_*`, not `POSTGRES_*`).
- **The signing key (Phase 2):** an ephemeral key is allowed only for an **allow-list** of profiles (`dev`, `test`); any other profile without a key refuses to start, naming the missing properties. A block-list (`!prod`) would let a typo'd or new profile (`staging`) run with a throwaway key (*proven*: `JwtKeyConfigTest`, Phase 3 §3.0). A key pair is checked at startup by comparing moduli.
- **`prod` refuses to start without a real email sender** (Phase 1, on purpose, until Phase 9).
- **HikariCP pool size set explicitly per profile** (dev: 5): bigger isn't faster; the queue should do the waiting.

## Beans, proxies and what Spring wraps

- **`@Transactional`, `@Async` and scoped beans work through proxies.** A call through `this` skips the proxy (**self-invocation**), so the annotation silently does nothing; private methods are never proxied. Details: [jpa 03](../jpa/03-transactions.md); scoped proxies: [spring 03](03-filters-interceptors-and-request-scope.md).
- **Workflow / service split** follows from it: non-transactional orchestration (`RegistrationWorkflow`, `PasswordWorkflow`, `SessionWorkflow`, `MembershipWorkflow`) calls transactional services in other beans.
- **Boot adds every `Converter` bean to MVC's conversion service** (*verified*), so the JWT converter is a plain object, not a bean (Phase 2, your call).
- **`@EnableJpaAuditing` on its own `@Configuration`,** not the main class, or it activates in web slices with no JPA.
- **Inject interfaces** (`UserDetailsService`, not `TaskflowUserDetailsService`) so a test can provide a mock that fits.
- **Boot's `ObjectMapper` (via `Jackson2ObjectMapperBuilder`) registers Spring's `ProblemDetail` mixin**, so code writing a `ProblemDetail` with it (the filter-chain entry point) matches MVC's output.

## Build and tooling facts

- **Initializr no longer serves Boot 3** (`>=4.0.0` only). Generate with 4.x and downgrade the pom; Boot 4 renamed starters (`spring-boot-starter-webmvc` → `spring-boot-starter-web`; no `spring-boot-starter-flyway`, use `flyway-core`; five modular `*-test` starters → one `spring-boot-starter-test`).
- **Lombok is an annotation processor discovered implicitly from the classpath, and JDK 23+ disables implicit processing.** *What happened to us (Phase 0):* under JDK 25 Lombok silently generated nothing ("cannot find symbol" everywhere). Declare it in `maven-compiler-plugin`'s `annotationProcessorPaths`.
- **IntelliJ:** "Delegate build/run to Maven" runs the tests before starting the app (one failing test = no app); IntelliJ's own builder skips Maven plugin goals, so `/actuator/info` is empty unless `generate-resources` runs before build; `spring-boot:run` doesn't reload code (a deliberate failure "didn't work" because the app wasn't restarted).
- **Stage files by name;** `git commit -a` swept unrelated doc edits twice, and a pushed commit once referenced an unstaged test class.

## Interview questions

1. **How does Boot decide which auto-configurations apply, and how do you override one?** Conditional classes listed in `AutoConfiguration.imports`; `@ConditionalOnMissingBean` lets your bean win; `--debug` shows the report.
2. **How do you make a misconfigured deploy fail loudly?** Typed, validated `@ConfigurationProperties`; no defaults for secrets; allow-lists for anything dangerous (the ephemeral key), so unknown profiles fail closed.
3. **Why did your properties record throw `NullPointerException` at startup?** The compact constructor read the accessor, which isn't assigned until the constructor body finishes; validate the parameter.
4. **Why does `common` never import a feature?** Otherwise shared code ends up importing the whole app; it declares interfaces that features implement instead.
5. **What broke when you upgraded the JDK?** Lombok stopped generating: implicit annotation processing is off in JDK 23+.
