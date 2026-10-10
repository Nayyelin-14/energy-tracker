# Java → MySQL Stack

## How the pieces fit together (request flow)

```mermaid
flowchart TD
    App["🚀 Your App<br/><code>userRepository.save(user)</code>"]

    subgraph SPEC["Standard / Specification (API only — no implementation)"]
        JPA["<b>JPA</b><br/>Java Persistence API<br/><i>Object ↔ Table mapping rules</i><br/>@Entity, @Id, EntityManager"]
        JDBC["<b>JDBC</b><br/>Java Database Connectivity<br/><i>Java ⇄ DB communication API</i><br/>Connection, PreparedStatement"]
    end

    subgraph IMPL["Implementations"]
        HIB["<b>Hibernate ORM</b><br/>JPA implementation<br/><i>Generates SQL from JPA calls</i>"]
        DRV["<b>MySQL Driver</b><br/>JDBC implementation<br/><i>com.mysql:mysql-connector-j</i><br/>Sends JDBC requests to MySQL"]
    end

    subgraph DB["Actual Database"]
        PG[("<b>MySQL</b><br/>Data storage<br/>users table")]
    end

    App --> JPA
    JPA -. "spec followed by" .-> HIB
    App -. "uses" .-> JDBC
    HIB -->|"generates & runs SQL via"| JDBC
    JDBC -->|"protocol implemented by"| DRV
    DRV -->|"wire protocol"| PG
    PG -. "results return up the stack" .-> App

    classDef spec fill:#eef6ff,stroke:#2563eb,stroke-width:2px,color:#1e3a8a
    classDef impl fill:#ecfdf5,stroke:#059669,stroke-width:2px,color:#065f46
    classDef db fill:#fff7ed,stroke:#ea580c,stroke-width:2px,color:#7c2d12
    classDef app fill:#f5f3ff,stroke:#7c3aed,stroke-width:2px,color:#4c1d95

    class JPA,JDBC spec
    class HIB,DRV impl
    class PG db
    class App app
```

## What is what?

```mermaid
flowchart LR
    subgraph legend["Legend"]
        direction LR
        s["Specification / Standard<br/>(defines <i>what</i> — no code)"]
        i["Implementation<br/>(does <i>how</i> — real code)"]
        d["Database<br/>(stores data)"]
    end

    classDef spec fill:#eef6ff,stroke:#2563eb,stroke-width:2px,color:#1e3a8a
    classDef impl fill:#ecfdf5,stroke:#059669,stroke-width:2px,color:#065f46
    classDef db fill:#fff7ed,stroke:#ea580c,stroke-width:2px,color:#7c2d12

    class s spec
    class i impl
    class d db
```

| Thing                 | Full name                  | ဘာလဲ?                              | အဓိကအလုပ်                                                         | Sample usage                                      |
| --------------------- | -------------------------- | ---------------------------------- | ----------------------------------------------------------------- | ------------------------------------------------- |
| **JPA**               | Java Persistence API       | Standard / specification           | Java Object ↔ DB Table mapping အတွက် standard                     | `@Entity`, `@Id`, `EntityManager`                 |
| **Hibernate**         | Hibernate ORM              | JPA implementation                 | JPA instructions ကို တကယ်အလုပ်လုပ်အောင်လုပ်ပြီး SQL generate      | `userRepository.save(user)` နောက်ကွယ်မှာအလုပ်လုပ် |
| **JDBC**              | Java Database Connectivity | Java database API                  | Java application က DB ကို communicate လုပ်နိုင်အောင် standard API | `Connection`, `PreparedStatement`                 |
| **MySQL Driver**      | MySQL JDBC Driver         | MySQL-specific implementation       | JDBC request ကို MySQL ဆီ တကယ်ပို့                             | Maven: `com.mysql:mysql-connector-j`             |
| **MySQL**             | —                          | Actual database                    | Data သိမ်းထားတဲ့နေရာ                                              | `users` table                                     |

<!--
JPA
→ "Java object နဲ့ DB table ကို ဘယ်လို map မလဲဆိုတဲ့ standard"

Hibernate
→ "JPA ကို တကယ်အလုပ်လုပ်အောင်လုပ်ပြီး SQL ထုတ်ပေးတဲ့ ORM"

JDBC
→ "Java application နဲ့ database ကြား database API"

MySQL Driver
→ "JDBC နဲ့ MySQL ကြား bridge"

MySQL
→ "Data တကယ်သိမ်းတဲ့ database" -->
