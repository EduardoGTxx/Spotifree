# Declaración de Accesibilidad / Accessibility Statement

En Spotifree nos comprometemos a garantizar que la aplicación sea accesible y fácil de usar para todas las personas, independientemente de sus capacidades físicas, visuales, auditivas o cognitivas.

Creemos firmemente en el software libre inclusivo, donde el acceso a la música no tenga barreras.

---

## 🎯 Estándares y objetivos

Nos esforzamos por cumplir y alinearnos con las pautas de accesibilidad para contenido web y aplicaciones móviles **WCAG 2.1 en su nivel AA** (Web Content Accessibility Guidelines), así como con las directrices de diseño accesible de **Material Design 3** y las mejores prácticas de accesibilidad en Android.

---

## 🌟 Características de accesibilidad implementadas

1. **Lectores de pantalla (TalkBack)**:
   - Los elementos interactivos principales (botones de reproducción, pausa, navegación, ajuste de volumen y filtros) cuentan con etiquetas semánticas y `contentDescription` claros.
   - La navegación táctil y por exploración refleja un orden lógico de lectura.

2. **Contraste y diseño visual**:
   - Paleta de colores de alto contraste con modo oscuro nativo y modo AMOLED (negro puro `#000000`), facilitando la lectura para personas con baja visión o sensibilidad a la luz.
   - El color de acento (`#2979ff`) mantiene una relación de contraste adecuada frente a los fondos oscuros.

3. **Escalado tipográfico dinámico**:
   - La tipografía de la interfaz respeta la configuración de tamaño de texto del sistema operativo Android (escalado *sp* de fuentes).
   - El visor de letras de canciones cuenta con controles dedicados para ajustar el tamaño del texto según las necesidades del usuario.

4. **Objetivos táctiles (*Touch Targets*)**:
   - Los botones de control y elementos clicables respetan el tamaño mínimo recomendado de interacción táctil (al menos 48x48 dp), evitando toques accidentales y facilitando su uso para personas con destreza motora reducida.

5. **Reducción de movimiento**:
   - Las animaciones y transiciones de la interfaz respetan las configuraciones de reducción de movimiento y eliminación de animaciones del sistema Android.

6. **Subtítulos y letras**:
   - Letras sincronizadas en tiempo real con resaltado visual línea por línea, permitiendo seguir la pista musical de forma visual para usuarios con dificultades auditivas.

---

## ⚠️ Limitaciones conocidas y trabajo en curso

Aunque trabajamos activamente para mejorar la accesibilidad en cada actualización, actualmente reconocemos ciertas áreas en optimización:

* **Visualizaciones experimentales de audio**: Algunas animaciones del espectro de audio en pantalla completa son decorativas y pueden no ofrecer una representación en texto equivalente.
* **Letras de terceros**: La disponibilidad y precisión temporal de las letras sincronizadas depende de los repositorios públicos de LRC y puede variar según la canción.

---

## 📢 Cómo reportar barreras de accesibilidad

Si experimentas alguna dificultad de acceso, encuentras un control sin etiquetar o tienes sugerencias para hacer que Spotifree sea más inclusivo, tu opinión es fundamental.

Puedes notificárnoslo mediante:

* **GitHub Issues**: Crea un reporte en la sección de [Issues](https://github.com/EduardoGTxx/Spotifree/issues) utilizando la etiqueta o mención `accessibility`.
* **Correo electrónico**: Escríbenos a [121446228+EduardoGTxx@users.noreply.github.com](mailto:121446228+EduardoGTxx@users.noreply.github.com) con el asunto `[Accesibilidad Spotifree]`.

Nos comprometemos a revisar y priorizar las solicitudes de mejora en accesibilidad en nuestras versiones continuas.
