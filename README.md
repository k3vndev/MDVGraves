# MDVGraves 1.0.6

Plugin ligero de bolsas de muerte para Purpur/Paper 1.21.6 y Java 21.

## Comportamiento base

- Solo crea bolsas en los mundos configurados; por defecto, únicamente `world`.
- Captura exclusivamente los objetos que realmente iban a caer en `PlayerDeathEvent#getDrops()`.
- Coloca un bloque real `PLAYER_HEAD`, sin armor stands, hologramas ni entidades permanentes.
- Cualquier jugador puede abrir o romper una bolsa por defecto, salvo que el propietario tenga protección privada.
- Al romperla, los objetos restantes caen una sola vez.
- Sin abrir: expira en 7 días. Desde la primera apertura: expira en 20 minutos.
- Al vaciarse, se elimina.
- Una sola persona puede abrir la misma bolsa a la vez.
- SQLite persistente en `plugins/MDVGraves/graves.db`, con WAL, índice de expiración y objetos comprimidos.
- La limpieza ocurre cada 5 minutos; no existen tareas por tick.
- No fuerza chunks durante la limpieza. Una cabeza expirada en un chunk descargado se limpia cuando ese chunk vuelva a cargar.

## Novedades 1.0.6

### Regreso a la última bolsa

Comandos equivalentes:

```text
/graveback
/backgrave
/bolsaback
/mdvgraves back
/back grave
```

Permiso:

```text
mdvgraves.back
```

`/back grave` es interceptado de forma exacta y no reemplaza el `/back` normal de Essentials.
El plugin busca la bolsa activa más reciente del jugador, carga su chunk y busca una posición segura alrededor.

### Bolsas privadas por permiso

Permiso:

```text
mdvgraves.private
```

Las bolsas creadas mientras el jugador tiene este permiso quedan marcadas como privadas en SQLite.
Solo el propietario y quienes tengan `mdvgraves.admin` pueden abrirlas o romperlas. Opcionalmente también son inmunes a explosiones.

Las bolsas antiguas conservan su estado público porque la migración utiliza `owner_protected = 0` por defecto.

### Keep inventory por permiso

Permiso:

```text
mdvgraves.keepinventory
```

Al morir, el jugador conserva todos los objetos y MDVGraves no crea ninguna bolsa. El sistema no modifica por sí mismo la pérdida de experiencia.

## Configuración de utilidades

```yaml
utilities:
  back-grave:
    enabled: true
    intercept-back-grave: true
    cooldown-seconds: 30
    safe-search-radius: 3
    sound: 'entity.enderman.teleport'

  private-graves:
    enabled: true
    protect-from-explosions: true

  keep-inventory:
    enabled: true
```

## Permisos

| Permiso | Predeterminado | Uso |
|---|---:|---|
| `mdvgraves.admin` | OP | Administración y bypass de bolsas privadas |
| `mdvgraves.back` | false | Regresar a la última bolsa |
| `mdvgraves.back.cooldown.bypass` | OP | Ignorar cooldown de regreso |
| `mdvgraves.private` | false | Hacer privadas las nuevas bolsas del jugador |
| `mdvgraves.keepinventory` | false | Conservar inventario y no crear bolsa |
| `mdvgraves.texture.vip` | false | Textura VIP configurada |
| `mdvgraves.texture.admin` | OP | Textura administrativa configurada |

## Comandos

```text
/graveback
/back grave
/mdvgraves back
/mdvgraves info
/mdvgraves reload
/mdvgraves cleanup
/mdvgraves deleteall confirm
```

## Actualización sin borrar datos

1. Apaga el servidor.
2. Haz una copia de `plugins/MDVGraves/graves.db` y `config.yml`.
3. Reemplaza únicamente el JAR.
4. No borres la carpeta `plugins/MDVGraves`.
5. Añade el bloque `utilities:` y los mensajes nuevos a tu configuración existente.
6. Inicia el servidor. La columna `owner_protected` se añade automáticamente a SQLite.

## Compilar

Requiere Java 21 y Maven:

```bash
mvn clean package
```

El JAR sombreado queda en:

```text
target/MDVGraves-1.0.6.jar
```

También se incluye `.github/workflows/build.yml` para compilar mediante GitHub Actions.

## Pruebas esenciales

1. Conceder `mdvgraves.back`, morir y probar `/graveback` y `/back grave`.
2. Probar regreso entre mundos y junto a una bolsa rodeada parcialmente por bloques.
3. Conceder `mdvgraves.private`, crear una bolsa e intentar abrirla y romperla con otro jugador.
4. Confirmar que `mdvgraves.admin` puede acceder a una bolsa privada.
5. Probar explosión sobre una bolsa privada con `protect-from-explosions: true`.
6. Conceder `mdvgraves.keepinventory`, morir y confirmar que no se crea bolsa ni se duplican objetos.
7. Reiniciar con bolsas antiguas y nuevas para validar la migración de SQLite.
