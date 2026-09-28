# Запуск на Ubuntu Server 22.04

Цель: Ubuntu Server 22.04 x86_64, 16 ГБ ОЗУ. Рекомендуется от 30 ГБ свободного диска; крупные файлы потребуют больше. Первый запуск требует Интернета для пакетов, Maven и образов Docker.

## 1. Распаковать архив

```bash
unzip OPTIMUS-HeatRoute-Final.zip
cd OPTIMUS-HeatRoute
find . -name '*.sh' -exec chmod +x {} +
sha256sum -c SHA256SUMS
```

## 2. Установить зависимости на новом сервере

```bash
bash scripts/install-ubuntu.sh
```

Устанавливаются Docker Engine 24.0.9 и docker-compose 1.29.2. Существующий Docker не удаляется и не заменяется. Для обязательного старого Compose используется отдельный стенд с совместимой версией Engine; подробности в docs/DEPLOYMENT.md.

## 3. Собрать и запустить

```bash
sudo env OPTIMUS_CONTEST=1 bash deploy.sh
sudo docker-compose ps
curl --fail http://127.0.0.1:8080/actuator/health
```

Dockerfile собирает исходники с тестами, включая расчёт 17 целей. Сборка занимает несколько минут. Java и Maven на хосте не нужны: они входят в образы. Первый запуск создаёт `.env` со случайным паролем БД.

Интерфейс: http://127.0.0.1:8080 . Swagger: http://127.0.0.1:8080/swagger-ui.html . Готовая карта: http://127.0.0.1:8080/result-viewer.html .

## 4. Открыть из Windows

Для отдельного Ubuntu-сервера в PowerShell (замените логин и IP):

```powershell
ssh -L 8080:127.0.0.1:8080 ubuntu@192.168.1.100
```

Для VirtualBox с NAT сначала пробросьте гостевой SSH-порт 22 на порт хоста 2222:

```powershell
ssh -p 2222 -L 8080:127.0.0.1:8080 ubuntu@127.0.0.1
```

Оставьте SSH-окно открытым и зайдите на http://localhost:8080 в Windows. На Ubuntu должен работать OpenSSH Server; при необходимости: `sudo apt-get install -y openssh-server`.

## 5. Выполнить расчёт

«Открыть конкурсный датасет» → «Рассчитать варианты» → дождаться 17/17 → выбрать вариант → «Скачать GeoJSON». Кнопка протокола показывает объяснения и диагностику. Полный сценарий выступления: docs/DEMO.md.

## 6. Сохранить приёмочный протокол на Ubuntu

```bash
sudo bash scripts/acceptance-contest.sh
```

Проверяется среда и PostGIS, выполняется новый HTTP-расчёт, проверяется GeoJSON и полный охват, создаётся карта. Затем запускаются 50 клиентов на малых синтетических заданиях. Протоколы: evidence/acceptance-latest и evidence/contest.

## 7. Управление

```bash
sudo bash deploy.sh status
sudo bash deploy.sh logs
sudo bash deploy.sh stop
```

Тома БД и расчётов сохраняются. Для повторного запуска выполните шаг 3. Сохраните `.env`; не используйте `down -v`, если данные нужны.

## Быстрый запуск готового JAR

```bash
sudo env OPTIMUS_CONTEST=1 bash deploy.sh prebuilt
```

Этот вариант не пересобирает Java-код. Основная конфигурация выше собирает проект из исходников, как предусмотрено ТЗ. Без Docker при установленной Java 11: `bash start.sh --local`; это отдельный файловый профиль без PostgreSQL.
