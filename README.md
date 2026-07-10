# MDVGraves 1.0.0

Plugin ligero de bolsas de muerte para Purpur/Paper 1.21.6 y Java 21.

## Comportamiento

- Solo actúa en los mundos configurados; por defecto, únicamente `world`.
- Captura exclusivamente `PlayerDeathEvent#getDrops()`, después de otros plugins por prioridad `HIGHEST` y `softdepend` con MMOItems/MythicLib.
- Coloca un bloque real `PLAYER_HEAD`, sin armor stands, hologramas ni entidades permanentes.
- Cualquier jugador puede abrir o romper la bolsa por defecto.
- Al romperla, los objetos restantes se dropean una sola vez.
- Sin abrir: expira en 7 días. Desde la primera apertura: expira en 20 minutos.
- Al vaciarse, se elimina.
- Una sola persona puede abrir la misma bolsa a la vez.
- SQLite persistente en `plugins/MDVGraves/graves.db`, con WAL, índice de expiración y objetos comprimidos.
- La limpieza ocurre cada 5 minutos; no existen tareas por tick.
- No fuerza chunks. Una cabeza expirada en chunk descargado se limpia cuando ese chunk vuelva a cargar.

## Compilar

Requiere Java 21 y Maven:

```bash
mvn clean package
```

El jar queda en:

```text
target/MDVGraves-1.0.0.jar
```

También se incluye `.github/workflows/build.yml` para compilarlo mediante GitHub Actions.

## Texturas por rango

En `config.yml`, cada entrada de `textures.ranks` usa un permiso y Base64:

```yaml
textures:
  ranks:
    admin:
      permission: 'mdvgraves.texture.admin'
      texture: 'BASE64'
    vip:
      permission: 'mdvgraves.texture.vip'
      texture: 'BASE64'
  default: 'BASE64_NORMAL'
```

Se evalúan de arriba hacia abajo y gana el primer permiso aplicable.

## Comandos

```text
/mdvgraves info
/mdvgraves reload
/mdvgraves cleanup
```

Permiso administrativo: `mdvgraves.admin`.

## Pruebas esenciales antes de producción

1. Morir en `world` y confirmar que solo se guardan los objetos que realmente iban a dropear.
2. Morir en `world5`, Nether y End para confirmar drops vanilla.
3. Abrir la misma bolsa con dos jugadores simultáneamente.
4. Retirar parte del contenido, cerrar y volver a abrir.
5. Romper una bolsa abierta y cerrada.
6. Probar items MMOItems soulbound/no-drop.
7. Reiniciar el servidor con bolsas existentes.
8. Probar una bolsa en chunk descargado y su expiración.
