---
name: java
description: Java development best practices
---

# Java Development

- Follow the project's existing code style.
- Prefer existing abstractions over introducing new ones.
- Add a regression test for every behavioral change.
- Run `mvn test` after each modification to verify nothing broke.
- Use records for simple data carriers, prefer `var` for local variables.
