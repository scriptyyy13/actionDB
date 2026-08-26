# ActionDB
Легковесный декларативный серверный движок с in-memory базой данных, реактивными SSE-подписками и безопасной виртуальной машиной. Логика описана в формате JSON.

> [!CAUTION]
> Это чисто учебный проект! Не рекомендуется использовать в продакшене на реальном проекте.

## Пример логики события

```json
[
  { "op": "VAR", "key": "vars.userId", "value": "${sys.uuid}" },
  { "op": "SET", "key": "users.${vars.userId}", "value": { "name": "${in.body.name}" } },
  { "op": "RETURN", "code": "200", "data": { "id": "${vars.userId}" } }
]
```
> Вся логика отдельных вызываемых триггеров (серверных сценариев) описывается в виде JSON файлов.

## Доступ к контексту выражений
В любом значении можно использовать подстановки `${...}`:
- `${in.body.field}` — тело HTTP POST-запроса / аргументы CALL_ACTION.
- `${in.query.field}` — URL query-параметры.
- `${in.auth.userId} / ${in.auth.roles}` — данные контекста авторизации.
- `${vars.myVar}` — локальные переменные контекста.
- `${sys.uuid} / ${sys.now} / ${sys.nano}` — динамические генераторы (UUID v4, timestamp).

## Справочник по командам (инструкциям)
> [!WARNING]
> Тут пропущена часть возможных аргументов этих команд, в связи с отсутствием документации следует ознакомиться с тем как виртуальная машина работает под капотом.

### Работа с СУБД
- **SET**: Запись в БД.
> `{ "op": "SET", "key": "users.1", "value": "Alice" }`

- **GET**: Чтение из БД в переменную.
> `{ "op": "GET", "key": "users.1", "as": "vars.user" }`

- **DELETE / DEL**: Удаление ключа.
> `{ "op": "DEL", "key": "users.1" }`

- **EXISTS**: Проверка существования ключа.
> `{ "op": "EXISTS", "key": "users.1", "as": "vars.isExists", "onFail": "@label" }`

- **INC / INCREMENT**: Инкремент числа.
> `{ "op": "INC", "key": "stats.counter", "value": 1, "as": "vars.newCount" }`

- **SET_IN_MAP**: Точечное изменение поля объекта.
> `{ "op": "SET_IN_MAP", "key": "user.profile", "field": "role", "value": "admin" }`

### Массивы и Очереди (Arrays)
- **ARRAY_PUSH**: Добавить элемент в конец.
> `{ "op": "ARRAY_PUSH", "key": "queue", "value": "item1" }`

- **ARRAY_UNSHIFT**: Добавить элемент в начало.
> `{ "op": "ARRAY_UNSHIFT", "key": "queue", "value": "item0" }`

- **ARRAY_POP**: Извлечь с конца (LIFO).
> `{ "op": "ARRAY_POP", "key": "queue", "as": "vars.last" }`

- **ARRAY_SHIFT**: Извлечь с начала (FIFO).
> `{ "op": "ARRAY_SHIFT", "key": "queue", "as": "vars.first" }`

- **ARRAY_LEN**: Получить длину массива.
> `{ "op": "ARRAY_LEN", "key": "queue", "as": "vars.length" }`

- **ARRAY_REMOVE**: Удалить элемент по значению.
> `{ "op": "ARRAY_REMOVE", "key": "queue", "value": "item1", "onFail": "@notFound" }`

- **ARRAY_FILTER**: Фильтрация списка объектов по полю.
> `{ "op": "ARRAY_FILTER", "key": "users.list", "field": "role", "value": "admin", "as": "vars.admins" }`

- **ARRAY_MAP**: Извлечение конкретного поля из списка объектов.
> `{ "op": "ARRAY_MAP", "key": "vars.admins", "field": "name", "as": "vars.adminNames" }`

### Управление потоком (Control Flow)

- **GOTO**: Безусловный переход на метку.
> `{ "op": "GOTO", "target": "@step2" }`

- **CHECK**: Проверка условия. В случае false — переход на onFail.
> `{ "op": "CHECK", "cond": "${in.body.age} >= 18", "onFail": "@tooYoung" }`

- **RETURN**: Успешное завершение работы экшена.
> `{ "op": "RETURN", "code": 200, "data": "${vars.result}" }`

- **THROW**: Завершение с ошибкой.
> `{ "op": "THROW", "code": 400, "message": "Bad request" }`

- **CALL / CALL_ACTION**: Вызов другого экшена.
> `{ "op": "CALL", "action": "utils.hash", "args": { "input": "text" }, "as": "vars.res" }`

### Переменные, JSON и Утилиты
- **VAR / VAR_SET**: Установка переменной (поддерживает синтаксис key или as).
> `{ "op": "VAR", "key": "vars.total", "value": "(${vars.price} * 1.2)" }`

- **JSON_PARSE**: Парсинг JSON-строки в объект.
> `{ "op": "JSON_PARSE", "value": "${vars.str}", "as": "vars.obj" }`

- **JSON_STRINGIFY**: Сериализация объекта в JSON-строку.
> `{ "op": "JSON_STRINGIFY", "value": "${vars.obj}", "as": "vars.str" }`

- **DELAY / SLEEP**: Пауза выполнения в миллисекундах.
> `{ "op": "DELAY", "ms": 500 }`

### Криптография, безопасность и сеть

- **HASH**: Хеширование (SHA-256, MD5, HMAC).
> `{ "op": "HASH", "value": "${in.body.password}", "algo": "SHA-256", "as": "vars.hash" }`

- **JWT_SIGN**: Подпись JWT-токена.
> `{ "op": "JWT_SIGN", "secret": "key", "expiresIn": 3600, "payload": { "id": "1" }, "as": "vars.token" }`

- **JWT_VERIFY**: Проверка и декодирование JWT-токена.
> `{ "op": "JWT_VERIFY", "token": "${vars.token}", "secret": "key", "as": "vars.payload", "onFail": "@unauthorized" }`

- **HTTP / HTTP_REQUEST**: Внешний HTTP-запрос.
> `{ "op": "HTTP", "url": "https://api.example.com/data", "method": "GET", "as": "vars.httpRes" }`

## Взаимодействие

### Вызов экшенов
Вызвать экшен, лежащий в папке `./actions` можно сделать запрос по пути `/api/action/ИМЯ_ЭКШЕНА`
Пример просто экшена:
- GET `/api/action/wallet.deposit?amount=500`
```json
[
  {
    "op": "CHECK",
    "cond": "${in.query.amount} > 0",
    "onFail": "@invalidAmount"
  },
  {
    "op": "INC",
    "key": "users.${in.auth.userId}.balance",
    "value": "${in.query.amount}",
    "as": "vars.newBalance"
  },
  {
    "op": "ARRAY_PUSH",
    "key": "users.${in.auth.userId}.transactions",
    "value": {
      "type": "deposit",
      "amount": "${in.query.amount}",
      "timestamp": "${sys.now}"
    }
  },
  {
    "op": "RETURN",
    "code": 200,
    "data": {
      "success": true,
      "balance": "${vars.newBalance}"
    }
  },
  {
    "op": "THROW",
    "label": "@invalidAmount",
    "code": 400,
    "message": "Amount must be greater than zero"
  }
]
```
> [!NOTE]
> Этот экшен проверяет что сумма пополнения больше нуля, производит пополнение, добавляет запись о транзакции в историю и возвращает новый баланс.

### Подпись на события
Подписаться на определенный префикс можно вызвав экшен по адресу `/api/subscribe/ИМЯ_ЭКШЕНА`
Важно, что в экшене производятся проверки и в `vars.allowedPrefix` сохраняется префикс, который разрешено слушать

Пример такого экшена:
- GET `/api/subscribe/room.join?roomId=42` (само название у экшена с постфиксом `room.join.auth.json`)
```json
[
  { 
    "op": "CHECK", 
    "cond": "${in.auth.isAuthenticated} == true", 
    "onFail": "@unauthorized" 
  },
  { 
    "op": "VAR", 
    "key": "vars.allowedPrefix", 
    "value": "rooms.${in.query.roomId}." 
  },
  { 
    "op": "RETURN", 
    "code": 200, 
    "data": "Authorized" 
  },
  { 
    "op": "THROW", 
    "label": "@unauthorized", 
    "code": 401, 
    "message": "You must log in to join room stream" 
  }
]
```
> [!NOTE]
> Этот экшен проверяет авторизацию и устанавливает разрешенный для прослушивания префикс.
