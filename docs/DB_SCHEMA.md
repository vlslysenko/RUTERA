# RUTERA — эскиз схемы базы данных

**Версия:** 0.1 от 2026-10-27. **Статус:** эскиз для планирования, не DDL. **Источник решений:** `docs/REQUIREMENTS.md` v0.5, раздел 16 (дефолты подтверждены 27.10.2026; закрыты открытые вопросы 1, 6, 8, 9, 10, 11, 13, частично 2 и 12).

Стек: MySQL 8.4, Hibernate 7 с `@TenantId`, Flyway. 56 таблиц в 8 группах. Визуальная версия с ER-диаграммами: https://claude.ai/artifact/CaWzXPnFBAbA3WozsqBSVg.

Обозначения в таблицах: **F** = есть `firma_id` (@TenantId); PK = первичный ключ; `→ таблица` = внешний ключ; U = уникально в фирме; `?` = NULL допустим; «вычисл.» = производная таблица, которую можно пересчитать; «этап 2» = по плану этапов REQUIREMENTS.md.

## 0. Соглашения для всех таблиц

- **Ключи.** `id BIGINT AUTO_INCREMENT` во всех таблицах, как в SMDP; без UUID. При миграции standalone → cloud идентификаторы перенумеровываются утилитой по образцу модуля `utils` SMDP.
- **Арендатор.** У каждой бизнес-таблицы колонка `firma_id BIGINT NOT NULL` (на диаграммах значок F). В Hibernate она помечена `@TenantId`: фильтр добавляется ко всем запросам автоматически, при вставке заполняется из контекста. Сервисный слой дополнительно проверяет `entity.firma_id == context.firma_id` при чтении по id (второй рубеж). Таблицы без F общие для всех фирм.
- **Общие колонки.** В каждой бизнес-таблице (на диаграммах не показаны): `created_at DATETIME(6)`, `created_by`, `updated_at`, `updated_by`, `version INT` (оптимистическая блокировка, `@Version` как в SMDP). Soft delete колонкой `deleted BOOL` только у справочников: машины, водители, контрагенты, пользователи; остальные строки удаляются физически. `changenum` из SMDP не нужен.
- **Имена.** Таблицы и колонки по-английски в `snake_case`, единственное число (`vehicle`, `driver_absence`). Сущность `firma` оставлена как имя (так во всех документах проекта). Индексы `ix_<table>_<cols>`, уникальные `ux_…`, внешние ключи `fk_<table>_<col>`.
- **Перечисления.** Хранятся строками `VARCHAR(32)` (`@Enumerated(STRING)`), не порядковыми номерами (в SMDP `RoleOperator` ORDINAL запрещает менять порядок) и не типом `ENUM` MySQL (каждое новое значение = ALTER TABLE).
- **Время.** Всё в UTC: `DATETIME(6)`, соединение с `connectionTimeZone=UTC`. Часовой пояс фирмы (`firma.timezone`) только для показа. Данные тахографа по природе в UTC. Даты без времени (`due_date`, отпуска) — `DATE`.
- **Деньги.** `DECIMAL(14,2)` плюс `currency CHAR(3)` (ISO 4217) на той же строке; литры `DECIMAL(10,3)`, цены за единицу `DECIMAL(10,4)`. Курсы в `currency_rate`.
- **JSON.** `JSON`-колонки для гибких данных без поиска по ним: настройки, распознанные поля CMR, сырые строки импорта, параметры письма. Всё, по чему фильтруют или считают, — обычные колонки.
- **Индексы.** Каждый индекс бизнес-таблицы начинается с `firma_id`, уникальные ограничения тоже включают `firma_id` (`UNIQUE(firma_id, plate)`). Внешние ключи между таблицами одной фирмы объявлены в базе; совпадение фирмы у обеих сторон проверяет сервисный слой.
- **Кодировка.** `utf8mb4`, коллация по умолчанию `utf8mb4_0900_ai_ci`; сортировка по языку пользователя через `COLLATE` в `ORDER BY`, карта языков → коллаций расширена на 26 языков (идея `LangServiceSession.getCurrentCollation` из SMDP).

### Мультитенантность по сравнению с SMDP

В SMDP условие `idfirma = ?` пишется вручную в каждом запросе, и забытое условие отдаёт данные всех фирм (`MULTITENANT_FIRMA_NULL_LEAK.md`). В RUTERA колонка `firma_id` помечена `@TenantId`: `CurrentTenantIdentifierResolver` отдаёт фирму из контекста запроса, Hibernate добавляет фильтр ко всем запросам и заполняет колонку при вставке, а сервисный слой проверяет `entity.firma_id == context.firma_id` при чтении по id (второй рубеж). Единственное исключение — `app_user`: вход ищет логин до того, как известна фирма, поэтому фильтр там ручной и покрыт тестом.

## Карта групп

| Группа | Таблицы | Связана с |
|---|---|---|
| 1. Фирма, пользователи, роли | firma, app_user, custom_role, user_privilege, remember_me_token, activation_token, totp_recovery_code, api_token, subscription | всеми через firma_id; driver.user_id |
| 2. Автопарк | vehicle, vehicle_document, driver_vehicle_assignment, odometer_reading, vehicle_position, vehicle_live, gps_device | водители (назначения), тахограф (VIN, GNSS), топливо, рейсы |
| 3. Водители и HR | driver, driver_card, driver_document, driver_absence, driver_day, driver_pay_rate, driver_payment | app_user, тахограф (карта, часы, нарушения), рейсы (экипаж) |
| 4. Тахограф | tacho_file, tacho_activity, tacho_vehicle_use, tacho_place, tacho_event, driver_hos_day, driver_violation | водители, машины, stored_file |
| 5. Рейсы, заказы, CMR | contractor, transport_order, trip, trip_driver, trip_stop, cmr_document, trip_plan, toll_tariff | машины, водители, фактуры, stored_file |
| 6. Фактуры и напоминания | invoice, invoice_line, payment, reminder_schedule, reminder_step, reminder_sent, currency_rate | контрагенты, рейсы, email_outbox |
| 7. Топливо | fuel_card, fuel_import, fuel_transaction | машины, водители, рейсы, stored_file |
| 8. Система | stored_file, audit_log, email_outbox, notification, firma_setting, app_setting, job_lock, flyway_schema_history | все группы |

## 1. Фирма, пользователи, роли

Корень мультитенантности и всё, что нужно для входа. Почти целиком заимствуется из SMDP, но с английскими именами и без топливных полей.

| Таблица | Назначение | Колонки (без общих) | Источник |
|---|---|---|---|
| `firma` | Фирма-арендатор. В standalone ровно одна запись (id = 1). | `id` PK, `name`, `short_code` U, `country`, `vat_id`?, `address`?, `email`?, `lang`, `timezone`, `base_currency`, `plan`, `vehicle_limit`, `ai_access`, `active`, `is_test`, `expires_at`? | SMDP Firma: общие поля, без ~30 топливных; TypSystemu → plan |
| `app_user` | Пользователь. role хранится строкой: OWNER, DISPATCHER, LOGISTICIAN, ACCOUNTANT, DRIVER, API, CUSTOM, SUPERUSER. firma_id = NULL только у суперпользователя сервиса. settings: стартовый экран, настройки таблиц. | `id` PK, `firma_id` → firma?, `login` U, `password_hash`, `role`, `custom_role_id` → custom_role?, `first_name`, `last_name`, `phone`?, `lang`, `totp_secret_enc`?, `totp_enabled`, `failed_logins`, `locked_until`?, `active`, `deleted`, `settings` | SMDP Operator: без MD5, RFID, LDAP, Windows, Azure |
| `custom_role` (F) | Роль с индивидуальным набором прав на уровне фирмы. | `id` PK, `name`, `level`, `privileges` | SMDP OwnOpRole |
| `user_privilege` (F) | Переопределение отдельного ключа права для пользователя (ключи вида fleet.vehicle/update). | `id` PK, `user_id` → app_user, `privilege_key`, `allowed` | SMDP operator2privileges |
| `remember_me_token` (F) | Токены «запомнить меня» с ротацией. | `id` PK, `user_id` → app_user, `series` U, `token_hash`, `expires_at`, `last_used_at`? | SMDP operator2token / RememberMeService |
| `activation_token` (F) | Приглашение или сброс пароля (kind = INVITE, RESET); в базе только SHA-256 хеш. | `id` PK, `user_id` → app_user, `kind`, `token_hash` U, `expires_at`, `used_at`? | SMDP InviteTokenService / OperatorActivationToken |
| `totp_recovery_code` (F) | Резервные коды двухфакторного входа. | `id` PK, `user_id` → app_user, `code_hash`, `used_at`? | SMDP TOTP-пакет |
| `api_token` (F) | Персональный токен для публичного API и MCP. Выдаётся только если firma.ai_access = true; права = права пользователя. | `id` PK, `user_id` → app_user, `name`, `token_prefix`, `token_hash` U, `scopes`?, `expires_at`?, `last_used_at`?, `revoked_at`? | новое (модуль «Управление через ИИ») |
| `subscription` (F, этап 2) | История подписок фирмы для облака (этап 2). В MVP достаточно колонок plan / vehicle_limit / ai_access в firma. | `id` PK, `plan`, `vehicle_limit`, `ai_access`, `valid_from`, `valid_to`?, `price`, `currency`, `status` | идея SMDP FirmaBillState |

**Связи:** `app_user.firma_id` → `firma`; `subscription.firma_id` → `firma`; `app_user.custom_role_id` → `custom_role`; `user_privilege.user_id` → `app_user`; `remember_me_token.user_id` → `app_user`; `activation_token.user_id` → `app_user`; `totp_recovery_code.user_id` → `app_user`; `api_token.user_id` → `app_user`; `driver.user_id` → `app_user` (водитель с входом в PWA).

**Заметки:**

- Права: как в SMDP, плоские ключи `module.entity/action` с картой по умолчанию на роль; `custom_role.privileges` и `user_privilege` переопределяют. Проверка через аннотацию `@RequiresPrivilege` + AOP, а не вручную в каждом контроллере.
- Суперпользователь сервиса: `app_user.firma_id = NULL`, флаг в `role = SUPERUSER`. Таблица `app_user` единственная бизнес-таблица без автоматического фильтра `@TenantId`: вход ищет пользователя по логину до того, как известна фирма. Фильтр по фирме в сервисе пользователей делается вручную и покрывается тестом.
- Уникальность логина глобальная (логин = e-mail), чтобы один адрес не мог завести два аккаунта в разных фирмах, пока нет механизма «один пользователь в нескольких фирмах».
- Дилеры и управляющие компании (`Firmazarzadzajaca` из SMDP) в эскиз не включены; при необходимости добавится таблица `managing_company` и nullable `firma.managing_company_id` одной миграцией.
- Тарифы: в MVP `firma.plan`, `firma.vehicle_limit`, `firma.ai_access`; лицензионные проверки в сервисном слое читают их. `subscription` появится с облаком и биллингом.

## 2. Автопарк

Машины, прицепы, документы с датами, назначения водителей, позиции на карте.

| Таблица | Назначение | Колонки (без общих) | Источник |
|---|---|---|---|
| `gps_device` (F, этап 2) | Трекер или подключение к аккаунту провайдера (TRACCAR, TELTONIKA, WIALON, WEBFLEET). Этап 2. | `id` PK, `provider`, `external_id`, `vehicle_id` → vehicle?, `active`, `config`? | новое |
| `vehicle` (F) | Единица автопарка: kind = TRUCK, TRACTOR, TRAILER, SEMI_TRAILER, VAN. Прицепы тоже здесь (default_trailer_id ссылается на ту же таблицу). Поля Euro, оси, масса, высота, расход нужны калькулятору поездки и платным дорогам. tacho_gen: NONE, GEN1, GEN2. Уникальность plate внутри фирмы. | `id` PK, `kind`, `plate` U, `vin`?, `make`?, `model`?, `euro_class`?, `axles`?, `gross_weight_kg`?, `height_mm`?, `avg_l_100km`?, `fuel_type`?, `tacho_gen`, `odometer_km`?, `status`, `default_driver_id` → driver?, `default_trailer_id` → vehicle?, `gps_device_id` → gps_device?, `last_vu_download_at`?, `deleted` | новое |
| `vehicle_document` (F) | Документы со сроком: REGISTRATION, INSURANCE_OC, INSURANCE_AC, INSPECTION, TACHO_CALIBRATION, LICENSE_COPY, TOLL_DEVICE, OTHER. Истекающие попадают в «требует внимания». | `id` PK, `vehicle_id` → vehicle, `kind`, `number`?, `valid_from`?, `valid_to`?, `cost`?, `currency`?, `file_id` → stored_file?, `remind_days` | новое |
| `driver_vehicle_assignment` (F) | Кто на какой машине в какой период (MANUAL или TACHO по вставке карты). Используется для привязки заправок и позиций к водителю. | `id` PK, `vehicle_id` → vehicle, `driver_id` → driver, `from_at`, `to_at`?, `source` | новое |
| `odometer_reading` (F) | Показания одометра из тахографа, заправок, GPS или вручную; основа расчёта расхода. | `id` PK, `vehicle_id` → vehicle, `at`, `km`, `source` | новое |
| `vehicle_position` (F, большая) | История позиций: GPS или GNSS из DDD Gen2 (source = GPS, TACHO_GNSS). Самая быстрорастущая таблица: индекс (firma_id, vehicle_id, at), партиции по месяцам, хранение 12 месяцев. | `id` PK, `vehicle_id` → vehicle, `at`, `lat`, `lng`, `speed_kmh`?, `heading`?, `ignition`?, `source`, `raw`? | новое |
| `vehicle_live` (F, вычисл.) | Последняя позиция и состояние машины (1:1), чтобы карта и обзор дня не искали максимум в истории. | `vehicle_id` PK → vehicle, `at`, `lat`, `lng`, `speed_kmh`?, `driver_id` → driver?, `state` | новое |

**Связи:** `vehicle.gps_device_id` → `gps_device`; `vehicle_document.vehicle_id` → `vehicle`; `driver_vehicle_assignment.vehicle_id` → `vehicle`; `odometer_reading.vehicle_id` → `vehicle`; `vehicle_position.vehicle_id` → `vehicle`; `vehicle_live.vehicle_id` → `vehicle` (1 : 1); `vehicle.default_driver_id` → `driver` (default_driver_id); `driver_vehicle_assignment.driver_id` → `driver`; `vehicle_document.file_id` → `stored_file`.

**Заметки:**

- Прицеп как отдельная строка `vehicle` с kind = TRAILER или SEMI_TRAILER: у него свои документы (страховка, техосмотр) и своя масса, а связка тягач + прицеп задаётся в рейсе (`trip.trailer_id`) и по умолчанию в `vehicle.default_trailer_id`.
- Сервисные события (ТО, ремонты, шины) в требованиях не упомянуты; при необходимости добавится `vehicle_service_event` по образцу `vehicle_document`.
- Позиции: пока трекеров нет, `vehicle_position` наполняется GNSS-точками из DDD Gen2 (раз в 3 часа вождения плюс начало и конец смены), этого хватает для «где была машина вчера», но не для живой карты.

## 3. Водители и HR

Карточка водителя: документы, отпуска и больничные, рабочие дни, ставки и выплаты. Нарушения и часы приходят из группы 4.

| Таблица | Назначение | Колонки (без общих) | Источник |
|---|---|---|---|
| `driver` (F) | Водитель как сотрудник. user_id заполнен, если у водителя есть вход в кабинет (PWA); водитель без логина тоже возможен. | `id` PK, `user_id` → app_user?, `first_name`, `last_name`, `birth_date`?, `phone`?, `email`?, `country`?, `employment_from`?, `employment_to`?, `status`, `default_vehicle_id` → vehicle?, `notes`?, `deleted` | новое |
| `driver_card` (F) | Карты водителя (история: карта меняется раз в 5 лет). По card_number импорт DDD находит водителя. last_download_at даёт напоминание о выгрузке раз в 28 дней. | `id` PK, `driver_id` → driver, `card_number` U, `issuing_country`, `valid_from`?, `valid_to`?, `generation`?, `last_download_at`? | новое |
| `driver_document` (F) | Документы со сроком: LICENSE, CPC_CODE95, MEDICAL, PSYCHO, ADR, PASSPORT, VISA, WORK_PERMIT, OTHER. | `id` PK, `driver_id` → driver, `kind`, `number`?, `valid_from`?, `valid_to`?, `file_id` → stored_file?, `remind_days` | новое |
| `driver_absence` (F) | Отпуска и больничные: kind = SICK, VACATION, UNPAID, PARENTAL, OTHER. Свободная ниша на рынке, поэтому отдельная таблица, а не поле. | `id` PK, `driver_id` → driver, `kind`, `from_date`, `to_date`, `days`, `paid`, `note`?, `file_id` → stored_file?, `approved_by` → app_user? | новое |
| `driver_day` (F, вычисл.) | Календарь рабочих дней: kind = WORK, REST, ABSENCE, HOLIDAY. Заполняется из тахографа (source = TACHO) или вручную для машин без тахографа. countries: страны за день для суточных. | `id` PK, `driver_id` → driver, `day` U, `kind`, `source`, `driving_min`?, `work_min`?, `countries`?, `note`? | новое |
| `driver_pay_rate` (F) | Ставки с историей по датам; их же использует калькулятор поездки для стоимости водителя. | `id` PK, `driver_id` → driver, `valid_from`, `monthly_base`?, `per_diem_home`?, `per_diem_abroad`?, `per_km`?, `currency` | новое |
| `driver_payment` (F) | Выплаты за период (YYYY-MM): SALARY, PER_DIEM, BONUS, ADVANCE, DEDUCTION. Видны только владельцу и бухгалтеру (отдельный ключ права). | `id` PK, `driver_id` → driver, `period`, `kind`, `amount`, `currency`, `paid_at`?, `note`? | новое |

**Связи:** `driver.user_id` → `app_user`; `driver_card.driver_id` → `driver`; `driver_document.driver_id` → `driver`; `driver_absence.driver_id` → `driver`; `driver_day.driver_id` → `driver`; `driver_pay_rate.driver_id` → `driver`; `driver_payment.driver_id` → `driver`; `driver_hos_day.driver_id` → `driver`; `driver_violation.driver_id` → `driver`; `driver.default_vehicle_id` → `vehicle`.

**Заметки:**

- Карточка водителя собирается из `driver` + `driver_card` + `driver_document` + `driver_absence` + `driver_day` + `driver_payment` + из группы 4 `driver_hos_day` (часы по 561/2006) и `driver_violation`.
- `driver_day` и `driver_absence` намеренно разделены: отсутствие вводит человек и оно имеет документ и согласование, а день календаря в основном вычисляется из тахографа и пересчитывается при каждом импорте.
- Зарплата и суточные: только учёт сумм, без расчётной логики по странам (она различается и меняется). Расчёт по формулам фирмы можно добавить позже поверх `driver_day.countries` и `driver_pay_rate`.

## 4. Тахограф (DDD) и регламент 561/2006

Импортированные файлы, факты из них и вычисленные часы и нарушения. Самая объёмная группа по данным.

| Таблица | Назначение | Колонки (без общих) | Источник |
|---|---|---|---|
| `tacho_file` (F) | Загруженный DDD: kind = DRIVER_CARD, VEHICLE_UNIT; generation = GEN1, GEN2, GEN2V2; source = UPLOAD, API, EMAIL. sha256 уникален в фирме: повторная загрузка того же файла не создаёт дублей. Сам файл хранится в stored_file. | `id` PK, `kind`, `file_id` → stored_file, `sha256` U, `card_number`?, `vin`?, `generation`, `period_from`?, `period_to`?, `downloaded_at`?, `source`, `parse_status`, `parse_error`?, `driver_id` → driver?, `vehicle_id` → vehicle?, `uploaded_by` → app_user? | новое |
| `tacho_activity` (F, большая) | Интервалы активности с точностью до минуты: DRIVING, WORK, AVAILABILITY, REST, UNKNOWN; slot 1 или 2 (экипаж). Уникальность (driver_id, start_at): пересечение файлов не даёт дублей. Около 50–150 строк на водителя в день. | `id` PK, `driver_id` → driver, `driver_card_id` → driver_card?, `vehicle_id` → vehicle?, `start_at`, `end_at`, `activity`, `slot`, `file_id` → tacho_file | новое |
| `tacho_vehicle_use` (F) | Какие машины использовал водитель (из карты). vehicle_id подбирается по VIN или номеру; даёт одометр в odometer_reading и назначения в driver_vehicle_assignment. | `id` PK, `driver_id` → driver, `vin`?, `plate`?, `first_use_at`, `last_use_at`, `odometer_begin`?, `odometer_end`?, `vehicle_id` → vehicle?, `file_id` → tacho_file | новое |
| `tacho_place` (F) | Места начала и конца рабочего дня, пересечения границ (DAILY_START, DAILY_END, BORDER). Даёт страны для суточных в driver_day. | `id` PK, `driver_id` → driver, `at`, `kind`, `country`, `region`?, `odometer_km`?, `lat`?, `lng`? | новое |
| `tacho_event` (F) | События и неисправности из карты и блока: превышение скорости, езда без карты, сбой питания (kind = EVENT, FAULT). | `id` PK, `driver_id` → driver?, `vehicle_id` → vehicle?, `kind`, `code`, `start_at`, `end_at`?, `details`?, `file_id` → tacho_file | новое |
| `driver_hos_day` (F, вычисл.) | Итог дня по 561/2006: сколько вёл, работал, отдыхал, сколько раз в неделе удлинял вождение до 10 ч и сокращал отдых до 9 ч, остатки часов. Пересчитывается после каждого импорта; читают карточка водителя, обзор дня и калькулятор поездки. | `id` PK, `driver_id` → driver, `day` U, `driving_min`, `work_min`, `rest_min`, `daily_rest_min`?, `ext_driving_in_week`, `reduced_rest_in_week`, `weekly_driving_min`, `biweekly_driving_min`, `remaining_daily_min`, `remaining_weekly_min`, `next_weekly_rest_by`?, `computed_at` | новое |
| `driver_violation` (F, вычисл.) | Нарушение: rule (например DAILY_DRIVING_10H, BREAK_45_AFTER_4H30, DAILY_REST_11H, WEEKLY_REST_45H, TWO_WEEK_DRIVING_90H), severity по 2016/403: MINOR, SERIOUS, VERY_SERIOUS, MOST_SERIOUS. ack_* : диспетчер отметил «разобрано». | `id` PK, `driver_id` → driver, `rule`, `severity`, `start_at`, `end_at`, `actual_value`, `limit_value`, `vehicle_id` → vehicle?, `details`?, `ack_by` → app_user?, `ack_at`? | новое |

**Связи:** `tacho_activity.file_id` → `tacho_file`; `tacho_vehicle_use.file_id` → `tacho_file`; `tacho_place.driver_id` → `driver`; `tacho_event.file_id` → `tacho_file`; `tacho_activity.driver_id` → `driver`; `tacho_vehicle_use.vehicle_id` → `vehicle`; `tacho_event.vehicle_id` → `vehicle`; `driver_hos_day.driver_id` → `driver`; `driver_violation.driver_id` → `driver`.

**Механизм:** Путь данных тахографа: файл → факты → вычисленные часы и нарушения → экраны.

**Заметки:**

- Два слоя: факты из файла (`tacho_activity`, `tacho_vehicle_use`, `tacho_place`, `tacho_event`) никогда не редактируются и всегда ссылаются на `tacho_file`; вычисленные таблицы (`driver_hos_day`, `driver_violation`) можно удалить и пересчитать из фактов в любой момент.
- Движок 561/2006 работает по `tacho_activity` одного водителя за скользящее окно (последние 28 дней + текущая неделя) и пишет результат в `driver_hos_day` и `driver_violation`. Чистый код без AI; та же библиотека правил считает паузы и отдыхи в калькуляторе поездки.
- Данные блока тахографа (VU) пишутся в те же таблицы: активности с `driver_id = NULL` (карта не вставлена) и с привязкой по номеру карты, когда она была в слоте.
- Объём: 20 водителей × 100 строк × 250 дней ≈ 500 тысяч строк `tacho_activity` в год на фирму; для сотен фирм партиции по месяцам и индекс (firma_id, driver_id, start_at).
- Напоминания о выгрузке: карта раз в 28 дней (`driver_card.last_download_at`), блок раз в 90 дней (`vehicle.last_vu_download_at`); отдельная таблица не нужна.

## 5. Рейсы, заказы, контрагенты, CMR

Заказ клиента, его выполнение рейсом, точки погрузки и выгрузки, документ CMR. Калькулятор поездки и тарифы платных дорог — этап 2.

| Таблица | Назначение | Колонки (без общих) | Источник |
|---|---|---|---|
| `contractor` (F) | Контрагент: заказчик, перевозчик-субподрядчик, поставщик. lang и invoice_emails нужны напоминаниям должникам. | `id` PK, `name`, `vat_id`?, `country`, `address`?, `invoice_emails`?, `phone`?, `payment_days`?, `lang`?, `reminder_schedule_id` → reminder_schedule?, `is_customer`, `is_carrier`, `deleted` | новое |
| `transport_order` (F) | Заказ клиента: NEW, PLANNED, IN_PROGRESS, DONE, INVOICED, CANCELLED. Несколько заказов могут ехать одним рейсом (догруз). source = MANUAL, EXCHANGE (биржи, этап 3). | `id` PK, `contractor_id` → contractor, `number` U, `order_date`, `price`, `currency`, `cargo`?, `weight_kg`?, `status`, `trip_id` → trip?, `source`, `external_ref`? | новое |
| `trip` (F) | Рейс: PLANNED, RUNNING, DONE, CANCELLED. Стоимости считаются из заправок, тарифов и ставок водителя и кешируются. | `id` PK, `number` U, `vehicle_id` → vehicle, `trailer_id` → vehicle?, `status`, `planned_start`?, `planned_end`?, `actual_start`?, `actual_end`?, `km_planned`?, `km_actual`?, `cost_fuel`?, `cost_tolls`?, `cost_driver`?, `currency` | новое |
| `trip_driver` (F) | Экипаж рейса: MAIN, SECOND (два водителя для правил 561/2006). | `id` PK, `trip_id` → trip, `driver_id` → driver, `role` | новое |
| `trip_stop` (F) | Точки рейса по порядку: LOADING, UNLOADING, BORDER, REST, FUEL, CUSTOMS. Водитель в PWA отмечает actual_at и статус. | `id` PK, `trip_id` → trip, `order_id` → transport_order?, `seq`, `kind`, `name`?, `address`, `country`, `lat`?, `lng`?, `planned_at`?, `actual_at`?, `status` | новое |
| `cmr_document` (F) | CMR: фото или скан (file_id), статусы UPLOADED, OCR_DONE, CONFIRMED, SENT. fields: распознанные отправитель, получатель, места, груз. ocr_engine = TESSERACT, AI. | `id` PK, `trip_id` → trip?, `order_id` → transport_order?, `number`?, `status`, `file_id` → stored_file, `ocr_engine`?, `ocr_text`?, `fields`?, `invoice_id` → invoice?, `uploaded_by` → app_user? | новое |
| `trip_plan` (F, этап 2) | Сохранённый расчёт калькулятора поездки: маршрут для грузовика, паузы и отдыхи по 561/2006, ETA, разбивка стоимости. Этап 2. | `id` PK, `vehicle_id` → vehicle, `driver_ids`, `origin`, `destination`, `route_geometry`?, `distance_km`, `duration_min`, `breaks`, `eta`, `cost`, `created_by` → app_user | новое |
| `toll_tariff` (этап 2) | Свои таблицы тарифов платных дорог по странам (общие для всех фирм, без firma_id). Этап 2; позже можно заменить платным API. | `id` PK, `country`, `road_class`, `euro_class`?, `axles_min`?, `weight_min_kg`?, `rate_per_km`, `currency`, `valid_from` | новое |

**Связи:** `transport_order.contractor_id` → `contractor`; `transport_order.trip_id` → `trip`; `trip_driver.trip_id` → `trip`; `trip_stop.trip_id` → `trip`; `cmr_document.trip_id` → `trip`; `cmr_document.invoice_id` → `invoice`; `cmr_document.file_id` → `stored_file`; `trip.vehicle_id` → `vehicle`; `trip_driver.driver_id` → `driver`; `trip_plan.vehicle_id` → `vehicle`.

**Заметки:**

- Заказ и рейс разделены намеренно: малый перевозчик часто везёт два заказа одной машиной, а фактура выставляется по заказу. Для простых случаев интерфейс создаёт заказ и рейс одним действием.
- CMR может прийти раньше рейса (фото с телефона водителя): `trip_id` и `order_id` nullable, привязка делается диспетчером или по номеру заказа из OCR.
- `trip_stop` с координатами даёт точки для карты и для калькулятора; `actual_at` из PWA водителя (или из GPS по геозоне, этап 2) закрывает рейс.
- Биржи грузов (этап 3): заказ со `source = EXCHANGE` и `external_ref`; таблицы аккаунтов бирж добавятся позже.

## 6. Фактуры, оплаты, напоминания должникам

Учёт фактур и платежей в MVP, выставление и KSeF позже на тех же таблицах. Напоминания по расписанию с историей отправок.

| Таблица | Назначение | Колонки (без общих) | Источник |
|---|---|---|---|
| `currency_rate` | Курсы валют (ЕЦБ, бесплатно, раз в день) для дебиторки в базовой валюте фирмы. Общая таблица без firma_id. | `id` PK, `day`, `base`, `quote`, `rate`, `source` | новое |
| `invoice` (F) | Фактура: direction = SALES (наша, дебиторка), PURCHASE (входящая). status вычисляется из платежей и даты: OPEN, PARTIAL, PAID, OVERDUE, CANCELLED, DRAFT. source = MANUAL, IMPORT, ISSUED. number уникален в фирме по направлению. ksef_id — этап 3. | `id` PK, `direction`, `number` U, `contractor_id` → contractor, `issue_date`, `due_date`, `currency`, `net`, `vat`, `gross`, `paid_amount`, `status`, `trip_id` → trip?, `order_id` → transport_order?, `file_id` → stored_file?, `source`, `ksef_id`?, `reminders_on` | новое |
| `invoice_line` (F) | Позиции. В MVP (учёт) могут быть пустыми: достаточно сумм в шапке; обязательны при выставлении (этап 3). | `id` PK, `invoice_id` → invoice, `seq`, `description`, `qty`, `unit`?, `unit_price`, `vat_rate`, `net`, `vat`, `gross` | новое |
| `payment` (F) | Платёж по фактуре: TRANSFER, CASH, OFFSET. Сумма платежей даёт paid_amount и статус. | `id` PK, `invoice_id` → invoice, `paid_at`, `amount`, `currency`, `method`, `reference`?, `note`? | новое |
| `reminder_schedule` (F) | План напоминаний фирмы (один по умолчанию, другие на контрагента). | `id` PK, `name`, `is_default`, `send_hour`, `cc_emails`?, `stop_when_paid` | новое |
| `reminder_step` (F) | Шаг плана: за 3 дня до срока, +1, +7, +14, +30 дней после; шаблон письма на языке контрагента; escalate = копия владельцу. | `id` PK, `schedule_id` → reminder_schedule, `seq`, `offset_days`, `template_key`, `escalate` | новое |
| `reminder_sent` (F) | История напоминаний по фактуре: QUEUED, SENT, FAILED, BOUNCED. Отправка идёт через email_outbox (группа 8). Ручное напоминание из карточки пишется сюда же со step_id = NULL. | `id` PK, `invoice_id` → invoice, `step_id` → reminder_step?, `sent_at`, `to_emails`, `subject`, `status`, `outbox_id` → email_outbox?, `error`? | новое |

**Связи:** `invoice.contractor_id` → `contractor`; `invoice_line.invoice_id` → `invoice`; `payment.invoice_id` → `invoice`; `reminder_step.schedule_id` → `reminder_schedule`; `reminder_sent.invoice_id` → `invoice`; `reminder_sent.step_id` → `reminder_step`; `reminder_sent.outbox_id` → `email_outbox`; `contractor.reminder_schedule_id` → `reminder_schedule` (план контрагента).

**Механизм:** Напоминания должникам: ежедневная задача находит фактуры по шагам плана, письмо уходит через outbox, результат остаётся в истории.

**Заметки:**

- Суммы: DECIMAL(14,2) и код валюты на каждой строке; пересчёт в базовую валюту фирмы по `currency_rate` на дату фактуры только для отчётов, в базе хранится оригинал.
- Выставление фактур и KSeF (этап 3) не требуют новой структуры: заполняются `invoice_line`, `source = ISSUED`, `ksef_id`, PDF в `stored_file`. Нумерация выставляемых фактур: счётчик в `firma_setting`.
- Импорт фактур из бухгалтерской программы: CSV, тот же механизм, что у импортов топлива (`stored_file` + статус разбора).

## 7. Топливо и топливные карты

Карты, пакеты импорта и сами транзакции. Расход и стоимость рейса вычисляются, а не хранятся.

| Таблица | Назначение | Колонки (без общих) | Источник |
|---|---|---|---|
| `fuel_card` (F, этап 2) | Топливная карта: DKV, UTA, E100, SHELL, AS24, OMV, BP, OTHER. Привязана к машине или водителю; номер хранится маскированным. | `id` PK, `provider`, `card_number_masked`, `external_id`?, `vehicle_id` → vehicle?, `driver_id` → driver?, `valid_to`?, `active` | новое |
| `fuel_import` (F, этап 2) | Пакет импорта выгрузки провайдера (CSV/XML) или ответа API: сколько строк, сколько новых, сколько дублей. | `id` PK, `provider`, `file_id` → stored_file, `period_from`?, `period_to`?, `rows_total`, `rows_new`, `rows_dup`, `status`, `error`?, `imported_by` → app_user | новое |
| `fuel_transaction` (F, этап 2, большая) | Заправка или другая операция по карте: DIESEL, ADBLUE, PETROL, TOLL, SERVICE, OTHER. (provider, external_id) уникальны в фирме: повторный импорт безопасен. source = IMPORT, MANUAL, DRIVER_APP (фото чека + пробег). Данные для возврата VAT и акциза по странам. | `id` PK, `card_id` → fuel_card?, `vehicle_id` → vehicle?, `driver_id` → driver?, `at`, `provider`, `external_id` U, `station`?, `country`, `product`, `quantity`, `unit_price`?, `net`, `vat`?, `gross`, `currency`, `odometer_km`?, `import_id` → fuel_import?, `trip_id` → trip?, `source`, `receipt_file_id` → stored_file? | новое |

**Связи:** `fuel_transaction.card_id` → `fuel_card`; `fuel_transaction.import_id` → `fuel_import`; `fuel_transaction.vehicle_id` → `vehicle`; `fuel_transaction.driver_id` → `driver`; `fuel_transaction.trip_id` → `trip` (по времени рейса); `fuel_transaction.receipt_file_id` → `stored_file`.

**Механизм:** Импорт топливных карт: выгрузка провайдера становится транзакциями, которые привязываются к машине, водителю и рейсу.

**Заметки:**

- Привязка транзакции: машина по карте (`fuel_card.vehicle_id`) или по номеру из выгрузки; водитель по `driver_vehicle_assignment` на момент заправки; рейс по времени. Всё переопределяется вручную.
- Расход на 100 км не хранится: считается по разнице `odometer_km` между заправками «до полного бака» (`fuel_transaction.odometer_km` плюс `odometer_reading`). Отчёт по машине, водителю и рейсу — запрос, при необходимости представление.
- Каждый формат провайдера — отдельный класс-парсер; первый формат определит пилот (открытый вопрос 5). Эта группа целиком относится к этапу 2, но схема заложена сейчас, потому что `trip.cost_fuel` и калькулятор зависят от неё.

## 8. Система: файлы, аудит, почта, настройки

Сквозные таблицы без бизнес-смысла. Языковые тексты в базе не хранятся.

| Таблица | Назначение | Колонки (без общих) | Источник |
|---|---|---|---|
| `stored_file` (F) | Любой файл: CMR, DDD, выгрузка топлива, PDF фактуры, скан документа, чек. storage = DISK (standalone) или S3 (облако, OVH); storage_key = путь или ключ бакета, всегда с префиксом firma_id. | `id` PK, `kind`, `storage`, `storage_key` U, `original_name`, `mime`, `size_bytes`, `sha256`, `uploaded_by` → app_user?, `uploaded_at` | новое |
| `audit_log` (большая) | Журнал действий: channel = UI, API, MCP, SYSTEM (действия через ИИ помечены). request_id совпадает с MDC в логах. firma_id nullable для действий суперпользователя. | `id` PK, `firma_id` → firma?, `user_id` → app_user?, `at`, `channel`, `module`, `action`, `entity`?, `entity_id`?, `summary`, `details`?, `request_id`, `ip`? | SMDP Dziennik, переписан в общий вид без полей устройств |
| `email_outbox` (F) | Очередь писем вместо JMS: сервис пишет строку в той же транзакции, планировщик отправляет (QUEUED, SENDING, SENT, FAILED) с повторами. Шаблоны Thymeleaf из SMDP cloud, язык из params. | `id` PK, `to_emails`, `cc_emails`?, `subject`, `template_key`, `params`, `attachments`?, `status`, `attempts`, `next_attempt_at`?, `sent_at`?, `error`? | SMDP EmailService + шаблоны; очередь новая |
| `notification` (F) | Уведомления в приложении (колокольчик в верхней панели): истекает документ, новое нарушение, просроченная фактура, ошибка импорта. | `id` PK, `user_id` → app_user, `kind`, `title`, `body`?, `link`?, `created_at`, `read_at`? | новое |
| `firma_setting` (F) | Настройки фирмы ключ → JSON: дни напоминаний о документах, ставки суточных по умолчанию, счётчик номеров фактур, часы отправки писем. | `id` PK, `key` U, `value` | новое |
| `app_setting` | Глобальные настройки, меняемые в работе (не секреты: пароли и ключи только в переменных окружения). | `key` PK, `value`, `updated_at` | идея SMDP SMDPConfig / addinfo |
| `job_lock` | Блокировка фоновых задач (ShedLock), чтобы в облаке с несколькими экземплярами напоминания и импорты не выполнялись дважды. | `name` PK, `lock_until`, `locked_at`, `locked_by` | SMDP cloud использует ShedLock на Redis; здесь на MySQL |
| `flyway_schema_history` | Таблица Flyway, создаётся и ведётся им. Заменяет addinfo.database_version и AppDbUpgraderService из SMDP. | `installed_rank` PK, `version`, `description`, `script`, `checksum`, `installed_on`, `success` | вместо SMDP AppDbUpgraderService |

**Заметки:**

- i18n: тексты интерфейса и писем живут в `messages_xx.properties` (решение по SMDP), в базе только коды языков (`firma.lang`, `app_user.lang`, `contractor.lang`). Пользовательские справочники (виды документов, статусы) — enum в коде с переводами в тех же файлах.
- Аудит пишется сервисным слоем через `@Audited`-аннотацию или явный вызов; MDC (firmaId, userId, requestId) ставится фильтром на входе запроса, чего в SMDP не было.
- `email_outbox` и `job_lock` вместе закрывают открытые вопросы 9 (без брокера) и работу в нескольких экземплярах в облаке.

## 9. MySQL и Flyway

- **Движок:** MySQL 8.4 LTS (или 9.x), InnoDB, `innodb_file_per_table`, `sql_mode` строгий. Standalone: контейнер MySQL в том же `docker compose`; облако: управляемая MySQL OVH или StatefulSet в k8s.
- **Flyway вместо `AppDbUpgraderService`:** миграции `src/main/resources/db/migration/V<n>__<описание>.sql` в модуле `common` (одно место, не две копии как в SMDP). `V1__baseline.sql` генерируется один раз из Hibernate-сущностей и правится руками, дальше только инкрементальные файлы. Повторяемые `R__views.sql` для представлений.
- **Проверка схемы:** `spring.jpa.hibernate.ddl-auto=validate` в тестах и при старте: сущности и миграции не могут разойтись незаметно. CI поднимает MySQL в контейнере и прогоняет все миграции с нуля.
- **Standalone:** при старте приложения перед `flyway.migrate()` выполняется `mysqldump` в `backup/pre-migrate-<ts>.sql.gz` (поведение `AppDbUpgraderService` сохранено), при ошибке миграции приложение не стартует и пишет понятное сообщение.
- **Облако:** миграции выполняет k8s Job (init-контейнер того же образа с `flyway migrate`) до выкатки новых подов; правило «миграция совместима с предыдущей версией кода» (сначала добавить колонку, потом удалить старую через релиз).
- **Партиции и объёмы:** `tacho_activity`, `vehicle_position`, `audit_log`, `fuel_transaction` — партиции `RANGE` по месяцу (`at`/`start_at`); у партиционированных таблиц в MySQL ключ партиции входит в первичный ключ, поэтому у них `PRIMARY KEY (id, at)` и нет внешних ключей на них. Хранение: позиции 12 месяцев, активности тахографа 2 года (законный минимум 1 год), аудит 2 года, затем удаление партицией.
- **Тестовые данные:** сид-скрипт `R__demo_data.sql` только для профиля `demo` (фирма с 5 машинами и 6 водителями, как у пилота).

### Большие таблицы

| Таблица | Рост | Хранение | Индексы и партиции |
|---|---|---|---|
| `tacho_activity` | 100–150 на водителя в день | 2 года | (firma_id, driver_id, start_at); партиция по месяцу start_at |
| `vehicle_position` | до 2 880 на машину в день при GPS раз в 30 с | 12 месяцев | (firma_id, vehicle_id, at); партиция по месяцу |
| `fuel_transaction` | 1–3 на машину в день | без ограничения | (firma_id, at), (firma_id, vehicle_id, at), UNIQUE(firma_id, provider, external_id) |
| `audit_log` | сотни в день на фирму | 2 года | (firma_id, at), (firma_id, entity, entity_id); партиция по месяцу |
| `email_outbox` | десятки в день | 90 дней после отправки | (status, next_attempt_at) |

## 10. Что взято из SMDP

Схема SMDP на 90 % про топливные устройства, поэтому заимствуется только фундамент. Исходники SMDP только для чтения.

| RUTERA | SMDP | Как |
|---|---|---|
| firma, app_user, custom_role, user_privilege, remember_me_token, activation_token, totp_recovery_code | Firma, Operator, OwnOpRole, operator2privileges, operator2token, OperatorActivationToken, TOTP | с доработкой: английские имена, без топливных полей, без MD5/RFID/LDAP/Azure, role строкой |
| audit_log | Dziennik | переписан: общий вид, канал UI/API/MCP, request_id, без полей устройств |
| email_outbox | EmailService + Thymeleaf-шаблоны cloud, EmailNotificationRequest | механизм писем взят, очередь JMS заменена таблицей |
| flyway_schema_history | AppDbUpgraderService, addinfo.database_version, updatesql | заменено Flyway; сохранён бэкап mysqldump перед миграцией |
| job_lock | ShedLock на Redis (cloud) | тот же ShedLock, провайдер JDBC |
| общие колонки, soft delete, @Version | deleted, optlockversion, datecreate/datemod, idusercreate/idusermod | как есть, имена по-английски; changenum не берём |
| всё остальное (автопарк, водители, тахограф, рейсы, фактуры, топливо) | нет аналога в SMDP | новое |

## 11. Что ещё стоит решить до первой миграции

- Один пользователь в нескольких фирмах (например бухгалтер на аутсорсе): сейчас не поддерживается, логин глобально уникален. Если нужно с первого дня, `app_user` делится на `account` (вход) и `membership` (фирма + роль).
- Прицеп в той же таблице `vehicle` или отдельная `trailer`: выбрана одна таблица; если у прицепов появится много своих полей, разделим.
- Карта и история позиций: хранить ли позиции GPS вообще в MySQL или сразу в Traccar (у него своя БД). Эскиз хранит, потому что GNSS из DDD идёт только к нам.
- Тарифы платных дорог: ручное ведение таблиц по странам — объём работы и актуальность; альтернатива — платный сервис позже.
- Глубина хранения тахографических данных и позиций (значения выше — предложение).

## История изменений

| Версия | Дата | Изменения |
|---|---|---|
| 0.1 | 2026-10-27 | Первый эскиз: 8 групп, соглашения, заметки по MySQL и Flyway, заимствования из SMDP; соглашения подтверждены в тот же день (REQUIREMENTS.md v0.5) |
