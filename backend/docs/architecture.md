# Home Energy Tracker — Architecture

> **Conceptual architecture.** This diagram represents the target design of the system as specified in the reference diagram. It is not a claim that every component or connection is currently implemented.

## Diagram

```mermaid
flowchart TD
    CLIENT["Client"]
    IOT["IoT"]
    GW["API Gateway"]

    DEV["Device Service"]
    USER["User Service"]
    MYSQL[("MySQL")]

    ING["Ingestion Service"]
    KAFKA1[("Kafka")]
    USAGE["Usage Service"]
    TSDB[("Time-Series Database")]

    INS["Insight Service"]
    AI["AI"]
    KAFKA2[("Kafka")]
    ALERT["Alert Service"]

    CLIENT --> GW
    IOT --> GW

    GW --> DEV
    GW --> USER
    GW --> ING
    GW --> INS

    DEV --> MYSQL
    USER --> MYSQL

    ING --> KAFKA1
    KAFKA1 --> USAGE
    USAGE --> TSDB
    USAGE --> KAFKA2
    KAFKA2 --> ALERT

    INS --> AI
    INS -.-> USAGE

    ALERT -.-> USER
    ALERT --> MYSQL

    classDef client fill:#1e293b,stroke:#94a3b8,stroke-width:1px,color:#f1f5f9
    classDef gateway fill:#312e81,stroke:#818cf8,stroke-width:2px,color:#e0e7ff
    classDef service fill:#0f172a,stroke:#38bdf8,stroke-width:1px,color:#e2e8f0
    classDef store fill:#052e16,stroke:#4ade80,stroke-width:1px,color:#dcfce7
    classDef queue fill:#2e1065,stroke:#a78bfa,stroke-width:1px,color:#ede9fe
    classDef ai fill:#422006,stroke:#fbbf24,stroke-width:1px,color:#fef3c7

    class CLIENT,IOT client
    class GW gateway
    class DEV,USER,ING,USAGE,INS,ALERT service
    class MYSQL,TSDB store
    class KAFKA1,KAFKA2 queue
    class AI ai
```

## Legend

| Arrow               | Meaning                                                       |
| ------------------- | ------------------------------------------------------------- |
| `A --> B` (solid)   | Primary connection between components                         |
| `A -.-> B` (dashed) | Secondary / cross-service relationship (shown dashed in the reference) |

## Flow summary

1. **Client** and **IoT** connect to the **API Gateway**
2. **API Gateway** connects to **Device Service**, **User Service**, **Ingestion Service**, and **Insight Service**
3. **Device Service** and **User Service** both use **MySQL**
4. **Ingestion Service** flows into **Kafka**, which flows into **Usage Service**
5. **Usage Service** stores data in the **Time-Series Database** and also flows into a second **Kafka**, which flows into **Alert Service**
6. **Insight Service** connects to **AI** and (dashed) to **Usage Service**
7. **Alert Service** connects (dashed) back to **User Service** and also uses **MySQL**
