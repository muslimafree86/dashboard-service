Snapshot of user-profile-managment's Flyway migrations (`src/main/resources/db/migration`), copied unchanged.

The integration tests apply them first, with that service's own history table (`flyway_schema_history`), and only
then start this service - exactly the production order. They are the contract this service reads: when
user-profile-managment adds a migration, copy it here and run `mvn verify`.
