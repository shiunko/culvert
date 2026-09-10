document.addEventListener("DOMContentLoaded", () => {
  var tablist = document.getElementById("app-tabs");
  var indicator = document.getElementById("tabs-indicator");
  if (!tablist) {
    return;
  }

  function tabButtons() {
    return Array.prototype.slice.call(tablist.querySelectorAll(".pf-tab"));
  }

  function moveIndicator(btn) {
    if (!indicator || !btn) {
      return;
    }
    indicator.style.width = btn.offsetWidth + "px";
    indicator.style.transform = "translateX(" + btn.offsetLeft + "px)";
  }

  // ============================================================
  // Tab 首次惰加载：面板首次变为可见时才拉取真实内容，在此之前容器只展示骨架屏（views.clj 的 ui.skeleton）。
  // 容器本身仍保留原有 hx-get/hx-trigger（120 秒轮询 + WS 自定义事件），这里只负责首次主动拉取。
  // ============================================================
  function lazyLoadPanel(panel) {
    var containers = panel.querySelectorAll('[data-loaded="false"]');
    containers.forEach(function (container) {
      var endpoint = container.getAttribute("hx-get");
      if (!endpoint || typeof htmx === "undefined") {
        return;
      }
      container.dataset.loaded = "pending";
      htmx.ajax("GET", endpoint, {target: container, swap: "innerHTML"}).then(function () {
        container.dataset.loaded = "true";
      });
    });
  }

  function activateTab(tabId, updateHistory) {
    var buttons = tabButtons();
    var target = null;
    buttons.forEach(function (btn) {
      var isActive = btn.getAttribute("data-tab") === tabId;
      btn.setAttribute("aria-selected", isActive ? "true" : "false");
      btn.setAttribute("tabindex", isActive ? "0" : "-1");
      if (isActive) {
        target = btn;
      }
    });
    if (!target) {
      return false;
    }
    var activePanel = null;
    document.querySelectorAll("[data-tab-panel]").forEach(function (panel) {
      var isActive = panel.getAttribute("data-tab-panel") === tabId;
      panel.hidden = !isActive;
      if (isActive) {
        activePanel = panel;
      }
    });
    if (activePanel) {
      lazyLoadPanel(activePanel);
    }
    moveIndicator(target);
    if (updateHistory) {
      var url = new URL(window.location.href);
      url.searchParams.set("tab", tabId);
      window.history.replaceState({}, "", url);
    }
    return true;
  }

  tablist.addEventListener("click", function (e) {
    var btn = e.target.closest(".pf-tab");
    if (btn) {
      activateTab(btn.getAttribute("data-tab"), true);
    }
  });

  tablist.addEventListener("keydown", function (e) {
    if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") {
      return;
    }
    var buttons = tabButtons();
    var currentIndex = buttons.findIndex(function (b) { return b.getAttribute("aria-selected") === "true"; });
    if (currentIndex === -1) {
      return;
    }
    var delta = e.key === "ArrowRight" ? 1 : -1;
    var nextIndex = (currentIndex + delta + buttons.length) % buttons.length;
    var nextBtn = buttons[nextIndex];
    activateTab(nextBtn.getAttribute("data-tab"), true);
    nextBtn.focus();
    e.preventDefault();
  });

  document.body.addEventListener("click", function (e) {
    var activator = e.target.closest('[data-action="tabs.activate"]');
    if (activator) {
      activateTab(activator.getAttribute("data-tab"), true);
    }
  });

  window.addEventListener("resize", function () {
    var buttons = tabButtons();
    var current = buttons.find(function (b) { return b.getAttribute("aria-selected") === "true"; });
    moveIndicator(current);
  });

  var initialTab = new URL(window.location.href).searchParams.get("tab");
  var buttons = tabButtons();
  var hasInitialTab = initialTab && buttons.some(function (b) { return b.getAttribute("data-tab") === initialTab; });
  if (hasInitialTab) {
    activateTab(initialTab, false);
  } else {
    var preSelected = buttons.find(function (b) { return b.getAttribute("aria-selected") === "true"; });
    if (preSelected) {
      moveIndicator(preSelected);
    }
  }

  // ============================================================
  // 管理员 Tab 内二级 Segmented Control：仅在 #admin-subtabs 作用域内生效，不写 URL，与一级 Tab 独立。
  // ============================================================
  var adminSubtabs = document.getElementById("admin-subtabs");
  if (adminSubtabs) {
    var activateSubtab = (function () {
      return function (subtabId) {
        Array.prototype.slice.call(adminSubtabs.querySelectorAll("[data-subtab]")).forEach(function (btn) {
          var isActive = btn.getAttribute("data-subtab") === subtabId;
          btn.setAttribute("aria-selected", isActive ? "true" : "false");
          btn.setAttribute("tabindex", isActive ? "0" : "-1");
        });
        document.querySelectorAll("[data-tab-panel-sub]").forEach(function (panel) {
          panel.hidden = panel.getAttribute("data-tab-panel-sub") !== subtabId;
        });
      };
    })();

    adminSubtabs.addEventListener("click", function (e) {
      var btn = e.target.closest("[data-subtab]");
      if (btn) {
        activateSubtab(btn.getAttribute("data-subtab"));
      }
    });

    adminSubtabs.addEventListener("keydown", function (e) {
      if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") {
        return;
      }
      var subButtons = Array.prototype.slice.call(adminSubtabs.querySelectorAll("[data-subtab]"));
      var currentIndex = subButtons.findIndex(function (b) { return b.getAttribute("aria-selected") === "true"; });
      if (currentIndex === -1) {
        return;
      }
      var delta = e.key === "ArrowRight" ? 1 : -1;
      var nextIndex = (currentIndex + delta + subButtons.length) % subButtons.length;
      var nextBtn = subButtons[nextIndex];
      activateSubtab(nextBtn.getAttribute("data-subtab"));
      nextBtn.focus();
      e.preventDefault();
    });
  }

  // ============================================================
  // Drawer：创建表单弹出层
  // ============================================================
  function drawerEls(name) {
    return {
      drawer: document.getElementById(name),
      backdrop: document.getElementById(name + "-backdrop"),
    };
  }

  function openDrawer(name, opener) {
    var els = drawerEls(name);
    if (!els.drawer) {
      return;
    }
    els.drawer.hidden = false;
    if (els.backdrop) {
      els.backdrop.hidden = false;
    }
    els.drawer.dataset.opener = opener ? opener.id || "" : "";
    var firstField = els.drawer.querySelector("input, select, textarea");
    if (firstField) {
      firstField.focus();
    }
  }

  function closeDrawer(name) {
    var els = drawerEls(name);
    if (!els.drawer) {
      return;
    }
    els.drawer.hidden = true;
    if (els.backdrop) {
      els.backdrop.hidden = true;
    }
    var openerId = els.drawer.dataset.opener;
    if (openerId) {
      var opener = document.getElementById(openerId);
      if (opener) {
        opener.focus();
      }
    }
  }

  document.body.addEventListener("click", function (e) {
    var openBtn = e.target.closest('[data-action="drawer.open"]');
    if (openBtn) {
      openDrawer(openBtn.getAttribute("data-drawer"), openBtn);
      return;
    }
    var closeBtn = e.target.closest('[data-action="drawer.close"]');
    if (closeBtn) {
      closeDrawer(closeBtn.getAttribute("data-drawer"));
    }
  });

  // ============================================================
  // Popover：次要说明文字的“？”弹出气泡（见 src/culvert/web/ui/popover.clj）
  // ============================================================
  function popoverContentFor(triggerId) {
    return document.getElementById(triggerId + "-content");
  }

  function closeAllPopovers() {
    document.querySelectorAll('[data-action="popover.toggle"]').forEach(function (btn) {
      var content = popoverContentFor(btn.getAttribute("data-popover"));
      if (content && !content.hidden) {
        content.hidden = true;
        btn.setAttribute("aria-expanded", "false");
      }
    });
  }

  document.body.addEventListener("click", function (e) {
    var trigger = e.target.closest('[data-action="popover.toggle"]');
    if (trigger) {
      var content = popoverContentFor(trigger.getAttribute("data-popover"));
      if (!content) {
        return;
      }
      var wasHidden = content.hidden;
      closeAllPopovers();
      if (wasHidden) {
        content.hidden = false;
        trigger.setAttribute("aria-expanded", "true");
      }
      e.stopPropagation();
      return;
    }
    if (!e.target.closest(".pf-popover-content")) {
      closeAllPopovers();
    }
  });

  document.body.addEventListener("keydown", function (e) {
    if (e.key !== "Escape") {
      return;
    }
    document.querySelectorAll(".pf-drawer").forEach(function (drawer) {
      if (!drawer.hidden) {
        closeDrawer(drawer.id);
      }
    });
    closeAllPopovers();
  });

  document.body.addEventListener("htmx:afterRequest", function (evt) {
    var form = evt.detail.elt;
    var drawerName = form && form.getAttribute && form.getAttribute("data-close-drawer-on-success");
    if (evt.detail.successful && drawerName) {
      closeDrawer(drawerName);
    }
  });

  // ============================================================
  // WebSocket 连接状态指示灯：main.js 触发自定义事件，这里只负责渲染
  // ============================================================
  document.body.addEventListener("ws-status", function (evt) {
    var indicatorEl = document.getElementById("ws-indicator");
    if (indicatorEl && evt.detail && evt.detail.state) {
      indicatorEl.setAttribute("data-state", evt.detail.state);
    }
  });
});
