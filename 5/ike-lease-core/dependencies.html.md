---
date_published: 1980-01-31
date_modified: 1980-01-31
canonical_url: https://ike.network/ike-lease-plugin/ike-lease-core/dependencies.html
---

# Project Dependencies

## [test](#test)

The following is a list of test dependencies for this project. These dependencies are only required to compile and run unit tests for the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| org.junit.jupiter | [junit-jupiter](https://junit.org/junit5/)[1] | 5.11.4 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |

## [provided](#provided)

The following is a list of provided dependencies for this project. These dependencies are required to compile the application, but should be provided by default when using the library:

| GroupId | ArtifactId | Version | Classifier | Type | Licenses |
| --- | --- | --- | --- | --- | --- |
| network.ike | [ike-base-parent](https://ike.network/ike-base-parent/)[3] | 15 | site-theme | zip | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[4] |

# Project Transitive Dependencies

The following is a list of transitive dependencies for this project. Transitive dependencies are the dependencies of the project dependencies.

## [test](#test_2)

The following is a list of test dependencies for this project. These dependencies are only required to compile and run unit tests for the application:

| GroupId | ArtifactId | Version | Type | Licenses |
| --- | --- | --- | --- | --- |
| org.apiguardian | [apiguardian-api](https://github.com/apiguardian-team/apiguardian)[5] | 1.1.2 | jar | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
| org.junit.jupiter | [junit-jupiter-api](https://junit.org/junit5/)[1] | 5.11.4 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |
| org.junit.jupiter | [junit-jupiter-engine](https://junit.org/junit5/)[1] | 5.11.4 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |
| org.junit.jupiter | [junit-jupiter-params](https://junit.org/junit5/)[1] | 5.11.4 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |
| org.junit.platform | [junit-platform-commons](https://junit.org/junit5/)[1] | 1.11.4 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |
| org.junit.platform | [junit-platform-engine](https://junit.org/junit5/)[1] | 1.11.4 | jar | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |
| org.opentest4j | [opentest4j](https://github.com/ota4j-team/opentest4j)[7] | 1.3.0 | jar | [The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[4] |

# Project Dependency Graph

## [Dependency Tree](#dependency-tree)

- network.ike:ike-lease-core:jar:6-SNAPSHOT ** 
  
  | IKE Working-Set Leases — Core |
  | --- |
  | **Description: **The lease protocol (the exact port of lease.sh v2, golden-tested against a frozen reference) and the git-state materializer, as a plain-Java JPMS library with no IDE dependency — one core, thin hosts (IKE-Network/ike-issues#1057, #1067). The IntelliJ plugin embeds it; lease.sh execs its CLIs; the ws: goals may depend on it directly, which is what retires the goal-to-$HOME-script coupling (IKE-Network/ike-issues#1005). **URL: **[https://ike.network/ike-lease-plugin/ike-lease-core/](https://ike.network/ike-lease-plugin/ike-lease-core/)[8] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[4] |
  
    - org.junit.jupiter:junit-jupiter:jar:5.11.4 (test) ** 
      
      | JUnit Jupiter (Aggregator) |
      | --- |
      | **Description: **Module "junit-jupiter" of JUnit 5. **URL: **[https://junit.org/junit5/](https://junit.org/junit5/)[1] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |
      
          - org.junit.jupiter:junit-jupiter-api:jar:5.11.4 (test) ** 
            
            | JUnit Jupiter API |
            | --- |
            | **Description: **Module "junit-jupiter-api" of JUnit 5. **URL: **[https://junit.org/junit5/](https://junit.org/junit5/)[1] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |
            
                  - org.opentest4j:opentest4j:jar:1.3.0 (test) ** 
                    
                    | org.opentest4j:opentest4j |
                    | --- |
                    | **Description: **Open Test Alliance for the JVM **URL: **[https://github.com/ota4j-team/opentest4j](https://github.com/ota4j-team/opentest4j)[7] **Project Licenses: **[The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[4] |
                  - org.junit.platform:junit-platform-commons:jar:1.11.4 (test) ** 
                    
                    | JUnit Platform Commons |
                    | --- |
                    | **Description: **Module "junit-platform-commons" of JUnit 5. **URL: **[https://junit.org/junit5/](https://junit.org/junit5/)[1] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |
                  - org.apiguardian:apiguardian-api:jar:1.1.2 (test) ** 
                    
                    | org.apiguardian:apiguardian-api |
                    | --- |
                    | **Description: **@API Guardian **URL: **[https://github.com/apiguardian-team/apiguardian](https://github.com/apiguardian-team/apiguardian)[5] **Project Licenses: **[The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)[6] |
          - org.junit.jupiter:junit-jupiter-params:jar:5.11.4 (test) ** 
            
            | JUnit Jupiter Params |
            | --- |
            | **Description: **Module "junit-jupiter-params" of JUnit 5. **URL: **[https://junit.org/junit5/](https://junit.org/junit5/)[1] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |
          - org.junit.jupiter:junit-jupiter-engine:jar:5.11.4 (test) ** 
            
            | JUnit Jupiter Engine |
            | --- |
            | **Description: **Module "junit-jupiter-engine" of JUnit 5. **URL: **[https://junit.org/junit5/](https://junit.org/junit5/)[1] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |
            
                  - org.junit.platform:junit-platform-engine:jar:1.11.4 (test) ** 
                    
                    | JUnit Platform Engine API |
                    | --- |
                    | **Description: **Module "junit-platform-engine" of JUnit 5. **URL: **[https://junit.org/junit5/](https://junit.org/junit5/)[1] **Project Licenses: **[Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html)[2] |
    - network.ike:ike-base-parent:zip:site-theme:15 (provided) ** 
      
      | IKE Base Parent |
      | --- |
      | **Description: **Tier 0 foundation parent for the IKE Network — the apex of the parent inheritance forest, inherited by ike-tooling, ike-docs, and ike-platform. Carries shared publishing metadata, GPG signing, and Maven Central publishing configuration. **URL: **[https://ike.network/ike-base-parent/](https://ike.network/ike-base-parent/)[3] **Project Licenses: **[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt)[4] |

# Licenses

**The Apache License, Version 2.0: **org.apiguardian:apiguardian-api, org.opentest4j:opentest4j

**Apache License, Version 2.0: **IKE Base Parent, IKE Working-Set Leases — Core

**Eclipse Public License v2.0: **JUnit Jupiter (Aggregator), JUnit Jupiter API, JUnit Jupiter Engine, JUnit Jupiter Params, JUnit Platform Commons, JUnit Platform Engine API

# Dependency File Details

| Total | Size | Entries | Classes | Packages | Java Version | Debug Information |
| --- | --- | --- | --- | --- | --- | --- |
| ike-base-parent-15-site-theme.zip | 3.4 kB | - | - | - | - | - |
| apiguardian-api-1.1.2.jar | 6.8 kB | 9 | 3 | 2 | 1.6 | Yes |
| junit-jupiter-5.11.4.jar | 6.4 kB | 5 | 1 | 1 | 9 | No |
| junit-jupiter-api-5.11.4.jar | 216.4 kB | 197 | 182 | 8 | 1.8 | Yes |
| junit-jupiter-engine-5.11.4.jar | 260.1 kB | 152 | 135 | 9 | 1.8 | Yes |
| junit-jupiter-params-5.11.4.jar | 591.6 kB | 388 | 354 | 22 | 1.8 | Yes |
| junit-platform-commons-1.11.4.jar | 142 kB | 88 | - | - | - | - |
|    • Root | - | 78 | 64 | 8 | 1.8 | Yes |
|    • Versioned | - | 10 | 4 | 1 | 9 | Yes |
| junit-platform-engine-1.11.4.jar | 246.8 kB | 177 | 158 | 10 | 1.8 | Yes |
| opentest4j-1.3.0.jar | 14.3 kB | 15 | 9 | 2 | 1.6 | Yes |
| 9 | 1.5 MB | 1031 | 906 | 62 | 9 | 7 |
| provided: 1 | provided: 3.4 kB | - | - | - | - | - |
| test: 8 | test: 1.5 MB | test: 1031 | test: 906 | test: 62 | 9 | test: 7 |
