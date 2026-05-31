# Architecture Diagrams

Diagrams are stored in `docs/diagrams/` as PlantUML source and exported PNG files.

To regenerate PNG files:

```pwsh
.\docs\diagrams\generate-diagrams.ps1
```

## Sequence: Login flow

![Login flow](./diagrams/sequence-auth-login.png)

Source: `docs/diagrams/sequence-auth-login.puml`

## Sequence: Refresh flow

![Refresh flow](./diagrams/sequence-auth-refresh.png)

Source: `docs/diagrams/sequence-auth-refresh.puml`

## Sequence: Logout flow

![Logout flow](./diagrams/sequence-auth-logout.png)

Source: `docs/diagrams/sequence-auth-logout.puml`

## Sequence: Asynchronous client creation

![Asynchronous client creation](./diagrams/sequence-async-client-create.png)

Source: `docs/diagrams/sequence-async-client-create.puml`

## Sequence: Observability data flow

![Observability data flow](./diagrams/sequence-observability-flow.png)

Source: `docs/diagrams/sequence-observability-flow.puml`
