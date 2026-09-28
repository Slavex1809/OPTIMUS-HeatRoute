# Развёртывание и эксплуатация

Пошаговый запуск: ../START-HERE.md.

| Компонент | Конфигурация |
|---|---|
| Хост | Ubuntu Server 22.04 x86_64, 16 ГБ |
| Контейнеризация | docker-compose 1.29.2; установщик Engine 24.0.9 |
| Сборка | Maven 3.9.9, JDK 11, тесты в Dockerfile |
| Приложение | Java 11, Spring Boot 2.6.3, Swagger UI 1.7.0 |
| БД | PostgreSQL 14, PostGIS 3.4, профиль postgres |
| Память | app до 8 ГБ, heap 6 ГБ; db до 3 ГБ |
| Хранилище | постоянные тома database и calculations |
| Порт | 127.0.0.1:8080 |

## Совместимость старого Compose

Engine 26 удалил поля Container/ContainerConfig; старый Compose может завершаться ошибкой при пересоздании контейнеров. Для обязательного Compose 1.29.2 установщик фиксирует Engine 24.0.9. Он не понижает установленную версию и не удаляет контейнеры. Для Engine 26+ используйте отдельную Ubuntu VM с указанным стеком. Это стенд для приёмки обязательного старого стека; обновление промышленной инфраструктуры требует согласованной проверки совместимости.

Источники: https://docs.docker.com/engine/deprecated/#container-and-containerconfig-fields-in-image-inspect ; https://github.com/docker/compose/issues/11742 ; https://download.docker.com/linux/ubuntu/dists/jammy/pool/stable/amd64/ .

## Настройки

deploy.sh генерирует `.env` со случайным паролем БД и приватными правами. Параметры: DB_PASSWORD, BIND_ADDRESS, HTTP_PORT, ENTRY_POLICY. cost соответствует вложенным результатам. nearest включает другую проверку ввода и требует нового расчёта. При обновлении сохраняйте `.env` и оба тома. Пароль существующей БД не изменяется простой заменой `.env`.

Приложение работает от UID 10001. Healthcheck проверяет /actuator/health, приложение ждёт готовности БД. Журналы ограничены тремя файлами по 10 МБ на контейнер. Первичная схема БД устанавливается только на пустой том. JAR, исходники и SQL входят в архив; образы Docker и кэш Maven не включены.

Встроенной авторизации нет; для удалённой демонстрации предусмотрен SSH-туннель, для корпоративного доступа нужен внешний прокси с аутентификацией.

## Резервное копирование

Дождитесь завершения заданий:

```bash
mkdir -p backup
sudo docker-compose stop app
sudo docker-compose exec -T db pg_dump -U heatroute -d heatroute -Fc > backup/heatroute.dump
sudo docker-compose run --rm --no-deps --entrypoint tar app -C /data -czf - . > backup/calculations.tar.gz
sudo cp .env backup/environment.env
sudo chmod 600 backup/environment.env
sudo docker-compose start app
```

Восстановление выполняется на пустом подготовленном стенде: остановите app, восстановите дамп через pg_restore в БД heatroute и распакуйте calculations.tar.gz в том /data приложения; используйте согласованный комплект с .env. Восстановление поверх существующих данных заменяет их и требует решения владельца.

Диагностика: `sudo docker-compose logs --tail=150 app db`. Остановка во время расчёта приводит к статусу FAILED после перезапуска; повторный запуск доступен через API. Инфраструктурный протокол формирует scripts/acceptance-contest.sh.
