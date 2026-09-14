// Culvert 控制台增强脚本：跨组件悬停联动、点击定位、端口频谱筛选与列表前端筛选。
// 纯渐进增强：所有行为都建立在服务端已渲染的 DOM 与 data-* 属性之上，缺失时静默降级。
// 见 docs/13-1 的 D7/D8、E4。
document.addEventListener("DOMContentLoaded", () => {
  function escapeSelector(value) {
    if (window.CSS && typeof window.CSS.escape === "function") {
      return window.CSS.escape(value);
    }
    return String(value).replace(/["\\]/g, "\\$&");
  }

  function resourceEls(key) {
    return Array.prototype.slice.call(
      document.querySelectorAll('[data-resource-key="' + escapeSelector(key) + '"]'),
    );
  }

  // ============================================================
  // 1. 跨组件悬停联动（接线图 / 频谱 / 列表卡片同一资源一起高亮）
  // ============================================================
  document.body.addEventListener("mouseover", function (e) {
    var el = e.target.closest("[data-resource-key]");
    if (!el) {
      return;
    }
    var key = el.getAttribute("data-resource-key");
    if (!key) {
      return;
    }
    resourceEls(key).forEach(function (node) {
      node.classList.add("is-hover");
    });
  });

  document.body.addEventListener("mouseout", function (e) {
    var el = e.target.closest("[data-resource-key]");
    if (!el || el.contains(e.relatedTarget)) {
      return;
    }
    var key = el.getAttribute("data-resource-key");
    if (!key) {
      return;
    }
    resourceEls(key).forEach(function (node) {
      node.classList.remove("is-hover");
    });
  });

  // ============================================================
  // 2. 点击定位：跳转 Tab（由 tabs.js 处理）+ 必要时切换二级面板 + 滚动高亮
  // ============================================================
  function highlight(el) {
    if (!el) {
      return;
    }
    el.scrollIntoView({behavior: "smooth", block: "center"});
    el.classList.add("pf-target-highlight");
    setTimeout(function () {
      el.classList.remove("pf-target-highlight");
    }, 3200);
  }

  function locateInPanel(key) {
    var candidates = resourceEls(key).filter(function (el) {
      return el.closest('[data-tab-panel="overview"]') === null;
    });
    if (candidates.length > 0) {
      highlight(candidates[0]);
      return true;
    }
    return false;
  }

  document.body.addEventListener("click", function (e) {
    var el = e.target.closest("[data-resource-key][data-tab]");
    if (!el) {
      return;
    }
    e.preventDefault();
    var key = el.getAttribute("data-resource-key");
    var subtab = el.getAttribute("data-subtab-target");
    if (subtab) {
      var btn = document.querySelector('[data-subtab="' + escapeSelector(subtab) + '"]');
      if (btn) {
        btn.click();
      }
    }
    // 目标列表可能因首次惰加载尚未渲染，短轮询等待出现。
    var attempts = 0;
    (function tryLocate() {
      attempts += 1;
      if (!locateInPanel(key) && attempts < 20) {
        setTimeout(tryLocate, 150);
      }
    })();
  });

  // ============================================================
  // 3. 端口频谱：协议筛选 + 悬停详情条
  // ============================================================
  function applySpectrumFilter(proto) {
    document.querySelectorAll('#port-spectrum [data-action="spectrum.filter"]').forEach(function (btn) {
      btn.setAttribute("aria-pressed", btn.getAttribute("data-proto") === proto ? "true" : "false");
    });
    document.querySelectorAll("#port-spectrum .pf-seg").forEach(function (seg) {
      var p = seg.getAttribute("data-proto") || "ALL";
      var hide = proto !== "ALL" && p !== proto && p !== "ALL";
      seg.classList.toggle("pf-row-hidden", hide);
    });
  }

  function spectrumHint() {
    var detail = document.getElementById("port-spectrum-detail");
    if (detail) {
      detail.innerHTML =
        '<span class="pf-spectrum-detail-hint">悬停上方色块查看端口分配、关联资源与负责人，点击色块可跳转定位。</span>';
    }
  }

  document.body.addEventListener("click", function (e) {
    var btn = e.target.closest('#port-spectrum [data-action="spectrum.filter"]');
    if (btn) {
      applySpectrumFilter(btn.getAttribute("data-proto"));
    }
  });

  document.body.addEventListener("mouseover", function (e) {
    var seg = e.target.closest("#port-spectrum .pf-seg");
    if (!seg) {
      return;
    }
    var detail = document.getElementById("port-spectrum-detail");
    if (!detail) {
      return;
    }
    var label = seg.getAttribute("data-seg-label") || "";
    var service = seg.getAttribute("data-seg-service") || "—";
    var owner = seg.getAttribute("data-seg-owner") || "—";
    var status = seg.getAttribute("data-seg-status") || "—";
    detail.innerHTML = "";
    var parts = [
      ['font-family:var(--pf-font-mono);font-weight:700;color:var(--pf-accent)', label],
      ["", " ｜ 所属资源: " + service],
      ["color:var(--pf-accent-2)", " ｜ 负责人: " + owner],
      ["", " ｜ 状态: " + status],
    ];
    parts.forEach(function (part) {
      var span = document.createElement("span");
      span.setAttribute("style", part[0]);
      span.textContent = part[1];
      detail.appendChild(span);
    });
  });

  document.body.addEventListener("mouseout", function (e) {
    if (e.target.closest("#port-spectrum .pf-seg")) {
      spectrumHint();
    }
  });

  // ============================================================
  // 4. 列表前端筛选（搜索 + 状态），HTMX 换出 tbody 后自动重放
  // ============================================================
  function applyListFilter(tbodyId) {
    var tbody = document.getElementById(tbodyId);
    if (!tbody) {
      return;
    }
    var scope = tbody.closest("[data-tab-panel-sub]") || document;
    var input = scope.querySelector('[data-list-search="' + tbodyId + '"]');
    var activeBtn = scope.querySelector('[data-list-filter="' + tbodyId + '"][aria-pressed="true"]');
    var query = input ? input.value.trim().toLowerCase() : "";
    var status = activeBtn ? activeBtn.getAttribute("data-filter") : "all";
    tbody.querySelectorAll("tr[data-resource-key]").forEach(function (row) {
      var text = row.getAttribute("data-search") || "";
      var rowStatus = row.getAttribute("data-status") || "";
      var matchQuery = !query || text.indexOf(query) !== -1;
      var matchStatus = status === "all" || rowStatus === status;
      row.classList.toggle("pf-row-hidden", !(matchQuery && matchStatus));
    });
  }

  document.body.addEventListener("input", function (e) {
    var input = e.target.closest("[data-list-search]");
    if (input) {
      applyListFilter(input.getAttribute("data-list-search"));
    }
  });

  document.body.addEventListener("click", function (e) {
    var btn = e.target.closest("[data-list-filter]");
    if (!btn) {
      return;
    }
    var tbodyId = btn.getAttribute("data-list-filter");
    var group = btn.closest(".pf-status-filter");
    if (group) {
      group.querySelectorAll("[data-list-filter]").forEach(function (b) {
        b.setAttribute("aria-pressed", b === btn ? "true" : "false");
      });
    }
    applyListFilter(tbodyId);
  });

  document.body.addEventListener("htmx:afterSwap", function (evt) {
    var id = evt.detail.target && evt.detail.target.id;
    if (id === "forward-tbody" || id === "caddy-tbody") {
      applyListFilter(id);
    }
    if (id === "port-spectrum") {
      spectrumHint();
    }
  });

  // 活动记录级别筛选（客户端过滤，服务端只输出全部条目）
  function applyActivityFilter(level) {
    document.querySelectorAll('#activity-feed [data-action="activity.filter"]').forEach(function (btn) {
      btn.setAttribute("aria-pressed", btn.getAttribute("data-filter") === level ? "true" : "false");
    });
    document.querySelectorAll("#activity-feed .pf-activity-item").forEach(function (item) {
      var sev = item.getAttribute("data-severity") || "info";
      item.classList.toggle("pf-row-hidden", level !== "all" && sev !== level);
    });
  }

  document.body.addEventListener("click", function (e) {
    var btn = e.target.closest('[data-action="activity.filter"]');
    if (btn) {
      applyActivityFilter(btn.getAttribute("data-filter"));
    }
  });

  // ============================================================
  // 5. ⌘K 命令面板：从 #command-registry 构建列表，命令只做导航/打开 Drawer
  // ============================================================
  var paletteEl = document.getElementById("command-palette");
  var paletteInput = document.getElementById("command-palette-input");
  var paletteList = document.getElementById("command-palette-list");
  var paletteEmpty = document.getElementById("command-palette-empty");
  var paletteCount = document.getElementById("command-palette-count");
  var paletteClear = document.querySelector('[data-action="palette.clear"]');
  var commandSources = [];
  var paletteItems = [];
  var paletteVisible = [];
  var paletteIndex = 0;
  var paletteLastFocused = null;

  function subsequenceMatch(text, query) {
    if (!query) {
      return true;
    }
    var t = String(text).toLowerCase();
    var qi = 0;
    for (var i = 0; i < t.length && qi < query.length; i++) {
      if (t[i] === query[qi]) {
        qi++;
      }
    }
    return qi === query.length;
  }

  function buildPaletteItems() {
    if (!paletteList) {
      return;
    }
    commandSources = Array.prototype.slice.call(
      document.querySelectorAll("#command-registry [data-command]"),
    );
    paletteList.innerHTML = "";
    paletteItems = [];
    commandSources.forEach(function (src) {
      var id = src.getAttribute("data-command");
      var label = src.getAttribute("data-command-label") || "";
      var desc = src.getAttribute("data-command-description") || "";
      var cat = src.getAttribute("data-command-category") || "";
      var keywords = src.getAttribute("data-command-keywords") || "";
      var item = document.createElement("div");
      item.className = "pf-palette-item";
      item.id = "cmd-item-" + id;
      item.setAttribute("role", "option");
      item.setAttribute("aria-selected", "false");
      item.setAttribute("data-command-id", id);
      item.setAttribute("data-search", (label + " " + desc + " " + cat + " " + keywords).toLowerCase());

      var main = document.createElement("div");
      main.className = "pf-palette-item-main";
      var iconWrap = document.createElement("span");
      iconWrap.className = "pf-palette-item-icon";
      var icon = src.querySelector("svg");
      if (icon) {
        iconWrap.appendChild(icon.cloneNode(true));
      }
      var text = document.createElement("span");
      text.style.minWidth = "0";
      var title = document.createElement("span");
      title.className = "pf-palette-item-title";
      title.textContent = label;
      var catSpan = document.createElement("span");
      catSpan.className = "pf-palette-item-cat";
      catSpan.textContent = cat;
      title.appendChild(catSpan);
      var d = document.createElement("span");
      d.className = "pf-palette-item-desc";
      d.textContent = desc;
      text.appendChild(title);
      text.appendChild(d);
      main.appendChild(iconWrap);
      main.appendChild(text);

      var hint = document.createElement("span");
      hint.className = "pf-palette-item-hint";
      hint.textContent = "↵";

      item.appendChild(main);
      item.appendChild(hint);
      paletteList.appendChild(item);
      paletteItems.push({el: item, src: src});
    });
  }

  function renderPalette() {
    if (!paletteInput) {
      return;
    }
    var query = (paletteInput.value || "").trim().toLowerCase();
    paletteVisible = [];
    paletteItems.forEach(function (entry) {
      var match = subsequenceMatch(entry.el.getAttribute("data-search"), query);
      entry.el.hidden = !match;
      entry.el.setAttribute("aria-selected", "false");
      if (match) {
        paletteVisible.push(entry);
      }
    });
    if (paletteIndex >= paletteVisible.length) {
      paletteIndex = 0;
    }
    if (paletteVisible.length > 0) {
      var current = paletteVisible[paletteIndex];
      current.el.setAttribute("aria-selected", "true");
      current.el.scrollIntoView({block: "nearest"});
      paletteInput.setAttribute("aria-activedescendant", current.el.id);
    } else {
      paletteInput.removeAttribute("aria-activedescendant");
    }
    if (paletteEmpty) {
      paletteEmpty.hidden = paletteVisible.length !== 0;
    }
    if (paletteCount) {
      paletteCount.textContent = paletteVisible.length + " 个命令";
    }
    if (paletteClear) {
      paletteClear.hidden = !paletteInput.value;
    }
  }

  function openPalette() {
    if (!paletteEl || !paletteEl.hidden) {
      return;
    }
    paletteLastFocused = document.activeElement;
    paletteEl.hidden = false;
    paletteIndex = 0;
    if (paletteInput) {
      paletteInput.value = "";
    }
    renderPalette();
    setTimeout(function () {
      if (paletteInput) {
        paletteInput.focus();
      }
    }, 0);
  }

  function closePalette() {
    if (!paletteEl || paletteEl.hidden) {
      return;
    }
    paletteEl.hidden = true;
    if (paletteLastFocused && typeof paletteLastFocused.focus === "function") {
      paletteLastFocused.focus();
    }
  }

  function executePaletteItem(entry) {
    if (!entry) {
      return;
    }
    closePalette();
    var src = entry.src;
    src.click();
    var thenSelector = src.getAttribute("data-command-then");
    if (thenSelector) {
      var target = document.querySelector(thenSelector);
      if (target) {
        target.click();
      }
    }
  }

  if (paletteEl) {
    buildPaletteItems();
    renderPalette();

    // Escape 必须在 capture 阶段拦截并 stopPropagation，避免 tabs.js 的全局 Esc 同时关闭 Drawer/Popover。
    document.addEventListener("keydown", function (e) {
      if (paletteEl.hidden) {
        return;
      }
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        closePalette();
      }
    }, true);

    document.addEventListener("keydown", function (e) {
      if ((e.metaKey || e.ctrlKey) && (e.key === "k" || e.key === "K")) {
        e.preventDefault();
        if (paletteEl.hidden) {
          openPalette();
        } else {
          closePalette();
        }
      }
    });

    if (paletteInput) {
      paletteInput.addEventListener("input", function () {
        paletteIndex = 0;
        renderPalette();
      });
      paletteInput.addEventListener("keydown", function (e) {
        if (e.key === "ArrowDown") {
          e.preventDefault();
          if (paletteVisible.length > 0) {
            paletteIndex = (paletteIndex + 1) % paletteVisible.length;
            renderPalette();
          }
        } else if (e.key === "ArrowUp") {
          e.preventDefault();
          if (paletteVisible.length > 0) {
            paletteIndex = (paletteIndex - 1 + paletteVisible.length) % paletteVisible.length;
            renderPalette();
          }
        } else if (e.key === "Enter") {
          e.preventDefault();
          executePaletteItem(paletteVisible[paletteIndex]);
        }
      });
    }

    if (paletteList) {
      paletteList.addEventListener("mouseover", function (e) {
        var item = e.target.closest(".pf-palette-item");
        if (!item || item.hidden) {
          return;
        }
        var idx = paletteVisible.findIndex(function (entry) { return entry.el === item; });
        if (idx >= 0 && idx !== paletteIndex) {
          paletteIndex = idx;
          renderPalette();
        }
      });
    }

    document.body.addEventListener("click", function (e) {
      if (e.target.closest('[data-action="palette.open"]')) {
        openPalette();
        return;
      }
      if (e.target.closest('[data-action="palette.clear"]')) {
        if (paletteInput) {
          paletteInput.value = "";
          paletteIndex = 0;
          renderPalette();
          paletteInput.focus();
        }
        return;
      }
      if (e.target.closest('[data-action="palette.close"]')) {
        closePalette();
        return;
      }
      var item = e.target.closest("#command-palette-list .pf-palette-item");
      if (item) {
        var entry = paletteItems.find(function (x) { return x.el === item; });
        executePaletteItem(entry);
      }
    });
  }

  // ============================================================
  // 6. Kiosk 大屏：实时时钟 + 连接状态灯（/wall 无 tabs.js，这里兜底 ws-status 渲染）
  // ============================================================
  var wallClock = document.getElementById("wall-clock");
  var wallDate = document.getElementById("wall-date");
  if (wallClock || wallDate) {
    var pad2 = function (n) { return (n < 10 ? "0" : "") + n; };
    var tickClock = function () {
      var now = new Date();
      if (wallClock) {
        wallClock.textContent = pad2(now.getHours()) + ":" + pad2(now.getMinutes()) + ":" + pad2(now.getSeconds());
      }
      if (wallDate) {
        wallDate.textContent = now.toLocaleDateString("zh-CN", {
          year: "numeric", month: "long", day: "numeric", weekday: "long",
        });
      }
    };
    tickClock();
    setInterval(tickClock, 1000);
  }

  document.body.addEventListener("ws-status", function (evt) {
    var state = evt.detail && evt.detail.state;
    if (!state) {
      return;
    }
    document.querySelectorAll("[data-ws-indicator]").forEach(function (el) {
      el.setAttribute("data-state", state);
    });
  });
});
