# Política de Seguridad / Security Policy

En Spotifree nos tomamos muy en serio la seguridad y la privacidad de nuestros usuarios. Agradecemos el esfuerzo de la comunidad por ayudarnos a mantener la aplicación segura y confiable.

---

## Versiones con soporte / Supported Versions

Actualmente, solo la rama principal y las versiones mayores recientes de la aplicación nativa en Kotlin reciben actualizaciones y parches de seguridad.

| Versión / Version | Con soporte / Supported | Notas |
| :--- | :---: | :--- |
| `2.x` (Kotlin nativo) | :white_check_mark: | Versión activa y recomendada |
| `< 2.0` (React/Capacitor) | :x: | Obsoleta (se recomienda migrar a 2.x) |

---

## Cómo reportar una vulnerabilidad / Reporting a Vulnerability

Si has descubierto una vulnerabilidad de seguridad en Spotifree, te pedimos que **no** abras un issue público ni reveles los detalles públicamente hasta que hayamos tenido la oportunidad de investigarla y resolverla.

Por favor, utiliza una de las siguientes vías para un reporte responsable:

1. **GitHub Private Vulnerability Reporting (Recomendado)**:
   Ve a la pestaña [Security > Advisories](https://github.com/EduardoGTxx/Spotifree/security/advisories/new) del repositorio y envía un reporte privado.

2. **Correo electrónico directo**:
   Envía los detalles a [121446228+EduardoGTxx@users.noreply.github.com](mailto:121446228+EduardoGTxx@users.noreply.github.com) indicando en el asunto: `[Vulnerabilidad Spotifree]`.

### ¿Qué información incluir en el reporte?

Para facilitar la reproducción y mitigación rápida, por favor incluye:

- Tipo o clasificación de la vulnerabilidad.
- Pasos detallados para reproducir el fallo o una prueba de concepto (PoC).
- Versión de Spotifree y versión de Android afectadas.
- Cualquier sugerencia de mitigación o parche si la tienes disponible.

### Proceso de respuesta

1. **Confirmación**: Nos esforzaremos por acusar recibo de tu reporte en un plazo máximo de 48 horas.
2. **Evaluación**: Analizaremos el impacto y la viabilidad de la solución.
3. **Corrección**: Desarrollaremos y probaremos un parche en una rama privada.
4. **Publicación**: Se publicará una nueva versión con el arreglo correspondiente y se dará el debido reconocimiento al investigador (salvo que prefiera mantenerse anónimo).
