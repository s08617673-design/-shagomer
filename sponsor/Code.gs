/**
 * Шагомер — таблица спонсора (Google Apps Script).
 *
 * Установка (один раз, 5 минут):
 * 1. Создайте новую Google-таблицу: sheets.new
 * 2. Меню «Расширения → Apps Script». Удалите всё в редакторе, вставьте этот файл, нажмите «Сохранить».
 * 3. В выпадающем списке функций выберите setup и нажмите «Выполнить». Разрешите доступ
 *    (Google предупредит, что приложение не проверено — «Дополнительно → Перейти»).
 *    Появятся листы «Шаги», «Выплаты», «Настройки», «Сводка».
 * 4. «Развернуть → Новое развёртывание → Тип: Веб-приложение»:
 *       Запуск от имени: Я;  У кого есть доступ: Все.
 *    Нажмите «Начать развёртывание» и скопируйте URL вида https://script.google.com/macros/s/…/exec
 * 5. Этот URL введите в приложении у каждого участника (поле «Адрес таблицы спонсора»).
 *
 * Как платить: в листе «Сводка» видно, кому сколько должны. Сделав перевод на карту,
 * добавьте строку в лист «Выплаты» (код участника, дата, баллов, сумма) — баланс
 * участника в приложении обновится при следующей отправке шагов.
 */

var STEPS_PER_POINT = 10000;
var MAX_POINTS_PER_DAY = 1;

function setup() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();

  var settings = getOrCreate(ss, 'Настройки');
  if (settings.getLastRow() === 0) {
    settings.getRange('A1:B3').setValues([
      ['Курс: 1 балл = сум', 5000],
      ['Шагов за балл', STEPS_PER_POINT],
      ['Макс. баллов в день', MAX_POINTS_PER_DAY]
    ]);
    settings.setColumnWidth(1, 200);
  }

  var steps = getOrCreate(ss, 'Шаги');
  if (steps.getLastRow() === 0) {
    steps.appendRow(['Код', 'Имя', 'Дата', 'Шаги', 'Баллы', 'Обновлено']);
    steps.getRange('1:1').setFontWeight('bold');
    steps.setFrozenRows(1);
  }

  var payouts = getOrCreate(ss, 'Выплаты');
  if (payouts.getLastRow() === 0) {
    payouts.appendRow(['Код', 'Дата', 'Баллов', 'Сумма, сум', 'Комментарий']);
    payouts.getRange('1:1').setFontWeight('bold');
    payouts.setFrozenRows(1);
  }

  var summary = getOrCreate(ss, 'Сводка');
  summary.clear();
  summary.getRange('A1:H1').setValues([[
    'Код', 'Имя', 'Дней с 10 000+', 'Баллов заработано', 'Баллов выплачено', 'К выплате, баллов', 'К выплате, сум', 'Шагов всего'
  ]]).setFontWeight('bold');
  summary.setFrozenRows(1);
  // Уникальные участники из листа «Шаги»; остальные колонки считаются формулами.
  summary.getRange('A2').setFormula('=IFERROR(SORT(UNIQUE(FILTER(Шаги!A2:A, Шаги!A2:A<>""))), "")');
  summary.getRange('B2').setFormula('=ARRAYFORMULA(IF(A2:A="", "", IFERROR(VLOOKUP(A2:A, Шаги!A:B, 2, FALSE), "")))');
  summary.getRange('C2').setFormula('=ARRAYFORMULA(IF(A2:A="", "", COUNTIFS(Шаги!A:A, A2:A, Шаги!D:D, ">="&Настройки!B2)))');
  summary.getRange('D2').setFormula('=ARRAYFORMULA(IF(A2:A="", "", SUMIF(Шаги!A:A, A2:A, Шаги!E:E)))');
  summary.getRange('E2').setFormula('=ARRAYFORMULA(IF(A2:A="", "", SUMIF(Выплаты!A:A, A2:A, Выплаты!C:C)))');
  summary.getRange('F2').setFormula('=ARRAYFORMULA(IF(A2:A="", "", D2:D - E2:E))');
  summary.getRange('G2').setFormula('=ARRAYFORMULA(IF(A2:A="", "", F2:F * Настройки!B1))');
  summary.getRange('H2').setFormula('=ARRAYFORMULA(IF(A2:A="", "", SUMIF(Шаги!A:A, A2:A, Шаги!D:D)))');
  summary.setColumnWidths(1, 8, 130);
  ss.setActiveSheet(summary);
}

/** Приём шагов от приложения. Тело запроса: {"id","name","days":{"2026-10-18":8450,...}} */
function doPost(e) {
  var lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    var data = JSON.parse(e.postData.contents);
    var id = String(data.id || '').trim();
    var name = String(data.name || '').trim();
    if (!id || !name) return reply({ error: 'id and name required' });

    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var settings = ss.getSheetByName('Настройки');
    var rate = Number(settings.getRange('B1').getValue()) || 5000;
    var perPoint = Number(settings.getRange('B2').getValue()) || STEPS_PER_POINT;
    var maxPerDay = Number(settings.getRange('B3').getValue()) || MAX_POINTS_PER_DAY;

    var sheet = ss.getSheetByName('Шаги');
    var rows = sheet.getDataRange().getValues();
    var index = {};
    for (var r = 1; r < rows.length; r++) index[rows[r][0] + '|' + rows[r][2]] = r + 1;

    var now = new Date();
    var days = data.days || {};
    Object.keys(days).forEach(function (date) {
      var steps = Math.max(0, Math.floor(Number(days[date]) || 0));
      var points = Math.min(Math.floor(steps / perPoint), maxPerDay);
      var row = [id, name, date, steps, points, now];
      var at = index[id + '|' + date];
      if (at) sheet.getRange(at, 1, 1, 6).setValues([row]);
      else sheet.appendRow(row);
    });

    var paidPoints = 0, paidAmount = 0;
    var pay = ss.getSheetByName('Выплаты').getDataRange().getValues();
    for (var p = 1; p < pay.length; p++) {
      if (String(pay[p][0]).trim() === id) {
        paidPoints += Number(pay[p][2]) || 0;
        paidAmount += Number(pay[p][3]) || 0;
      }
    }
    return reply({ ok: true, paidPoints: paidPoints, paidAmount: paidAmount, rate: rate });
  } catch (err) {
    return reply({ error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function doGet() {
  return reply({ ok: true, service: 'shagomer' });
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function getOrCreate(ss, name) {
  return ss.getSheetByName(name) || ss.insertSheet(name);
}
