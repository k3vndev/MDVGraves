# MDVGraves 1.0.8

Plugin ligero de bolsas de muerte para Purpur/Paper 1.21.6 y Java 21.

## Comportamiento base

- Solo crea bolsas en los mundos configurados; por defecto, únicamente `world`.
- Captura exclusivamente los objetos que realmente iban a caer en `PlayerDeathEvent#getDrops()`.
- Coloca un bloque real `PLAYER_HEAD`, sin armor stands, hologramas ni entidades permanentes.
- Cualquier jugador puede abrir o romper una bolsa por defecto, salvo que el propietario tenga protección privada.
- Al romperla, los objetos restantes caen una sola vez.
- Sin abrir: expira en 7 días. Desde la primera apertura: expira en 20 minutos.
- Al vaciarse, se elimina.
- Una sola persona puede abrir la misma bolsa a la vez por defecto. Si se desactiva el bloqueo, todos comparten el mismo inventario canónico.
- SQLite persistente en `plugins/MDVGraves/graves.db`, con WAL, índice de expiración y objetos comprimidos.
- La limpieza ocurre cada 5 minutos. La única comprobación frecuente recorre exclusivamente las bolsas que estén abiertas.
- No fuerza chunks durante la limpieza. Una cabeza expirada en un chunk descargado se limpia cuando ese chunk vuelva a cargar.


## Novedades 1.0.8

### Fruta de la Muerte (MMOItems CONSUMABLE)

MDVGraves reconoce por NBT el `type` e `id` reales de MMOItems y solo acepta el consumible configurado:

```yaml
utilities:
  death-fruit:
    enabled: true
    mmoitems-type: 'CONSUMABLE'
    mmoitems-id: 'FRUTA_DE_LA_MUERTE'
    use-lock-ms: 750
    pending-use-max-age-ms: 2000
    sound: 'entity.enderman.teleport'
```

El MMOItem debe ejecutar desde consola:

```text
mdvgraves deathfruit %player%
```

Se recomienda `disable-right-click-consume: true`. MDVGraves registra el clic antes de que MMOItems procese el item; si MMOItems ya descontó una unidad y el graveback falla, devuelve exactamente una fruta. Si MMOItems no la descontó, MDVGraves la consume únicamente después de un teleport exitoso. Sin tumba, mundo, destino seguro o teleport válido, la fruta no se pierde.

### Graveback administrativo

Admins y consola pueden forzar el regreso de un jugador aunque ese jugador no tenga `mdvgraves.back` ni pueda saltar su cooldown:

```text
/graveback Pedrito
/mdvgraves back Pedrito
```

Permiso del ejecutor:

```text
mdvgraves.back.others
```

Predeterminado: OP.

### Soporte para DIRT_PATH/FARMLAND

Al crear una tumba sobre suelos parciales configurados, MDVGraves estabiliza únicamente el bloque que queda debajo de la cabeza. Por defecto:

```yaml
settings:
  placement-support-fixes:
    enabled: true
    replacements:
      DIRT_PATH: DIRT
      FARMLAND: DIRT
```

La comprobación sucede solo al morir/crear una bolsa, sin scanners ni tareas nuevas. Si la persistencia de la tumba falla, el bloque original se restaura.

## Novedades 1.0.7

### Protección anti-duplicación al revisar bolsas

- Una bolsa que tenga al menos un inventario abierto no puede romperse mediante minería, explosiones, pistones, fluidos, fuego, física de bloques ni cambios de entidades.
- La ruta normal de rotura nunca se ejecuta mientras exista un visor activo.
- Existe un único inventario canónico por bolsa, incluso si `single-viewer-lock` se desactiva.
- Si otro plugin altera físicamente una bolsa abierta, MDVGraves cierra todos sus visores, persiste el contenido una sola vez y restaura la cabeza sin generar drops.

Configuración:

```yaml
settings:
  open-grave-integrity-check-ticks: 1
```

### Reemplazo de bloques finos

La bolsa puede sustituir directamente, sin soltar el bloque anterior:

- Alfombras de lana, musgo y musgo pálido.
- Capas de nieve.
- Trigo, zanahorias, patatas, remolachas, verrugas y demás cultivos.
- Hierba, helechos, flores, hongos, raíces, enredaderas y vegetación acuática.
- Pétalos, hojarasca, arbustos y otros bloques superficiales finos de 1.21.x.

Configuración:

```yaml
settings:
  replace-thin-blocks: true
```

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
| `mdvgraves.back.others` | OP | Forzar el regreso de otro jugador ignorando sus permisos/cooldown |
| `mdvgraves.back.cooldown.bypass` | OP | Ignorar cooldown de regreso |
| `mdvgraves.private` | false | Hacer privadas las nuevas bolsas del jugador |
| `mdvgraves.keepinventory` | false | Conservar inventario y no crear bolsa |
| `mdvgraves.texture.vip` | false | Textura VIP configurada |
| `mdvgraves.texture.admin` | OP | Textura administrativa configurada |

## Comandos

```text
/graveback
/graveback <jugador>
/back grave
/mdvgraves back
/mdvgraves back <jugador>
# Interno, solo consola para MMOItems:
/mdvgraves deathfruit <jugador>
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
target/MDVGraves-1.0.8.jar
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

## Pruebas anti-duplicación 1.0.7

1. Jugador A abre una bolsa y mueve objetos entre la bolsa y su inventario.
2. Jugador B intenta romperla: debe cancelarse sin cerrar la GUI ni soltar objetos.
3. Repetir con TNT, creeper, pistón, agua/lava y un cambio de bloque externo.
4. Forzar desde otro plugin que la cabeza pase a AIR: ambos inventarios deben cerrarse y la cabeza debe restaurarse con el contenido restante.
5. Morir sobre alfombra, nieve, trigo maduro y musgo: la cabeza debe ocupar ese bloque sin producir el drop sustituido.
