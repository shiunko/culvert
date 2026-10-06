document.addEventListener("DOMContentLoaded", () => {
  var __i18n = {
    copied: "已复制",
    copyFailed: "复制失败，请手动复制",
    networkError: "网络错误",
    cannotGetPwd: "无法获取密码",
    showPassword: "显示密码",
    hidePassword: "隐藏密码",
  };

  // ============================================================
  // Toast notification（静态 CSS，见 public/app.css 的 .pf-toast-*）
  // ============================================================
  function showToast(message, type) {
    type = type || "success";
    var container = document.getElementById("toast-container");
    if (!container) {
      container = document.createElement("div");
      container.id = "toast-container";
      container.className = "pf-toast-container";
      document.body.appendChild(container);
    }
    var variant = type === "error" ? "error" : type === "warning" ? "warning" : "success";
    var toast = document.createElement("div");
    toast.className = "pf-toast pf-toast--" + variant;
    var toastMessage = document.createElement("span");
    toastMessage.textContent = message;
    toast.appendChild(toastMessage);
    container.appendChild(toast);
    setTimeout(function () {
      toast.classList.add("pf-toast--leaving");
      setTimeout(function () {
        toast.remove();
      }, 300);
    }, 3000);
  }

  // ============================================================
  // Copy to clipboard
  // ============================================================
  function copyToClipboard(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        function () { showToast(__i18n.copied, "success"); },
        function () { showToast(__i18n.copyFailed, "error"); },
      );
    } else {
      var ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      try {
        document.execCommand("copy");
        showToast(__i18n.copied, "success");
      } catch (e) {
        showToast(__i18n.copyFailed, "error");
      }
      document.body.removeChild(ta);
    }
  }

  // ============================================================
  // WebSocket connection with auto-reconnect
  // ============================================================
  var wsReconnectDelay = 2000;
  var wsMaxReconnectDelay = 30000;
  var panelRefreshMap = {
    "forward-tbody": "refresh-forward",
    "caddy-tbody": "refresh-caddy",
    "docker-tbody": "refresh-docker",
    "smolvm-tbody": "refresh-smolvm",
    "quota-bar": "refresh-quota",
    "overview-stats": "refresh-overview",
    "topology-view": "refresh-topology",
    "port-spectrum": "refresh-spectrum",
    "activity-feed": "refresh-activity",
    "host-metrics": "refresh-host",
    "admin-users-tbody": "refresh-admin",
    "admin-containers-tbody": "refresh-admin-containers",
    "wall-kpis": "refresh-wall-kpis",
    "wall-topology": "refresh-wall-topology",
    "wall-alerts": "refresh-wall-alerts",
  };

  function getCsrfToken() {
    var meta = document.querySelector('meta[name="csrf-token"]');
    return meta ? meta.getAttribute("content") : "";
  }

  function getCredentials(card) {
    var endpoint = card && card.getAttribute("data-credential-endpoint");
    var id = card && card.getAttribute("data-resource-id");
    var csrf = getCsrfToken();
    if (!endpoint || !id || !csrf) {
      return Promise.reject(new Error("凭据请求参数不完整"));
    }
    var body = new URLSearchParams();
    body.set("id", id);
    body.set("_csrf", csrf);
    return fetch(endpoint, {
      method: "POST",
      headers: {"Content-Type": "application/x-www-form-urlencoded", "X-CSRF-Token": csrf},
      body: body.toString(),
      credentials: "same-origin",
    }).then(function (res) {
      if (!res.ok) {
        throw new Error("凭据请求失败");
      }
      return res.json();
    }).then(function (data) {
      if (!data.shellPass) {
        throw new Error("凭据响应无密码");
      }
      return data.shellPass;
    });
  }

  function scheduleWsReconnect() {
    console.log("[ws] " + wsReconnectDelay / 1000 + "s 后重连...");
    document.body.dispatchEvent(new CustomEvent("ws-status", {detail: {state: "reconnecting"}}));
    setTimeout(connectWebSocket, wsReconnectDelay);
    wsReconnectDelay = Math.min(wsReconnectDelay * 2, wsMaxReconnectDelay);
  }

  // 先用带 CSRF 的 POST /ws/ticket 换取一次性票据（30 秒有效），再用它升级 WebSocket；
  // 不再使用页面内预写的长期 token（见 src/culvert/web/AGENTS.md 与 server.clj 的 /ws 分流）。
  function fetchWsTicket() {
    var csrf = getCsrfToken();
    return fetch("/ws/ticket", {
      method: "POST",
      headers: {"Content-Type": "application/x-www-form-urlencoded", "X-CSRF-Token": csrf},
      body: "_csrf=" + encodeURIComponent(csrf),
      credentials: "same-origin",
    }).then(function (res) {
      if (!res.ok) {
        throw new Error("票据请求失败");
      }
      return res.json();
    }).then(function (data) {
      if (!data.ticket) {
        throw new Error("票据响应为空");
      }
      return data.ticket;
    });
  }

  function openWebSocket(ticket) {
    var protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    var wsUrl = protocol + "//" + window.location.host + "/ws?ticket=" + encodeURIComponent(ticket);

    try {
      var ws = new WebSocket(wsUrl);

      ws.onopen = function () {
        console.log("[ws] 已连接");
        wsReconnectDelay = 2000;
        document.body.dispatchEvent(new CustomEvent("ws-status", {detail: {state: "connected"}}));
      };

      ws.onmessage = function (event) {
        try {
          var msg = JSON.parse(event.data);
          if (msg.event === "refresh" && msg.panels) {
            msg.panels.forEach(function (panelId) {
              var triggerName = panelRefreshMap[panelId];
              if (triggerName) {
                var el = document.getElementById(panelId);
                // 尚未首次惰加载的面板（data-loaded="false"）不接受 WS 驱动的后台拉取；
                // 用户实际切换到该 Tab 时 tabs.js 会直接拉取最新数据，无需额外补发。
                if (el && el.dataset.loaded !== "false") {
                  htmx.trigger(el, triggerName);
                }
              }
            });
          } else if (msg.event === "stats" && msg.stats) {
            updateResourceBars(msg.stats);
          }
        } catch (e) {
          console.warn("[ws] 消息解析失败:", e);
        }
      };

      ws.onclose = function () {
        scheduleWsReconnect();
      };

      ws.onerror = function () {
        // onclose will fire after this, triggering reconnect
      };
    } catch (e) {
      scheduleWsReconnect();
    }
  }

  function connectWebSocket() {
    fetchWsTicket().then(openWebSocket, scheduleWsReconnect);
  }

  connectWebSocket();

  // ============================================================
  // Listen to HTMX show-toast custom event
  // ============================================================
  document.body.addEventListener("show-toast", function (evt) {
    if (evt.detail && evt.detail.message) {
      showToast(evt.detail.message, evt.detail.type || "success");
    }
  });

  document.body.addEventListener("htmx:afterRequest", function (evt) {
    var form = evt.detail.elt;
    if (evt.detail.successful && form && form.getAttribute("data-reset-on-success") === "true") {
      form.reset();
    }
  });

  document.body.addEventListener("keydown", function (evt) {
    if (evt.key !== "Escape") {
      return;
    }
    var input = evt.target.closest("[data-remark-cancel]");
    if (!input) {
      return;
    }
    var form = input.closest("form");
    var params = new URLSearchParams();
    if (form) {
      form.querySelectorAll("input[name]").forEach(function (field) {
        if (field.name !== "_csrf" && field.name !== "remark") {
          params.set(field.name, field.value);
        }
      });
      params.set("remark", input.value);
    }
    htmx.ajax("GET", input.getAttribute("data-remark-cancel") + "?" + params.toString(), {
      target: input.closest("td"),
      swap: "innerHTML",
    });
  });

  // ============================================================
  // 容器日志面板：关闭按钮
  // ============================================================
  document.body.addEventListener("click", function (e) {
    if (e.target.closest('[data-action="logs.close"]')) {
      e.preventDefault();
      closeLogPanel();
    }
    if (e.target.closest('[data-action="terminal.close"]')) {
      e.preventDefault();
      var terminalModal = document.getElementById("terminalModal");
      if (terminalModal) {
        terminalModal.close();
      }
    }
  });

  // ============================================================
  // 容器日志面板：htmx 换入 <dialog> 后打开，轮询换入正文后按需滚到底部
  // ============================================================
  document.body.addEventListener("htmx:afterSwap", function (evt) {
    var target = evt.detail.target;
    if (target.id === "password-modal-container") {
      var modal = document.getElementById("passwordModal");
      if (modal) {
        modal.showModal();
      }
    }
    if (target.id === "log-panel-container") {
      var logModal = document.getElementById("logModal");
      if (logModal) {
        logModal.showModal();
        scrollLogToEnd();
      }
    }
    if (target.id === "terminal-modal-container") {
      var terminalModal = document.getElementById("terminalModal");
      if (terminalModal) {
        terminalModal.showModal();
      }
    }
    if (target.id === "log-stream") {
      scrollLogToEnd();
    }
  });

  function logFollowEnabled() {
    var follow = document.getElementById("log-follow");
    return !follow || follow.checked;
  }

  function scrollLogToEnd() {
    var box = document.getElementById("log-stream");
    if (box && logFollowEnabled()) {
      box.scrollTop = box.scrollHeight;
    }
  }

  function closeLogPanel() {
    var logModal = document.getElementById("logModal");
    if (logModal) {
      logModal.close();
    }
  }

  // 面板关闭（按钮/背景/Esc）后清空容器，停掉内部的轮询元素，避免隐藏面板继续请求。
  document.body.addEventListener("close", function (e) {
    if (e.target && e.target.id === "logModal") {
      var container = document.getElementById("log-panel-container");
      if (container) {
        container.innerHTML = "";
      }
    }
  }, true);

  // ============================================================
  // Password modal: close and refresh after password change
  // ============================================================
  document.body.addEventListener("closePasswordModal", function () {
    var modalEl = document.getElementById("passwordModal");
    if (modalEl) {
      modalEl.close();
    }
    setTimeout(function () {
      window.location.reload();
    }, 400);
  });

  // ============================================================
  // Global click delegation
  // ============================================================
  document.body.addEventListener("click", function (e) {
    // 1. Copy credentials
    var copyBtn = e.target.closest("[data-copy], [data-copy-pass]");
    if (copyBtn) {
      if (copyBtn.hasAttribute("data-copy")) {
        copyToClipboard(copyBtn.getAttribute("data-copy"));
      } else if (copyBtn.hasAttribute("data-copy-pass")) {
        var containerId = copyBtn.getAttribute("data-copy-pass");
        var card = copyBtn.closest(".card");
        var input = card.querySelector(".cred-pass-input");
        if (input && input.type === "text") {
          copyToClipboard(input.value);
          } else {
            getCredentials(copyBtn.closest("[data-credential-endpoint]"))
              .then(copyToClipboard)
              .catch(() => showToast(__i18n.cannotGetPwd, "error"));
          }
      }
    }

    // 2. Toggle password visibility
    var togglePassBtn = e.target.closest("[data-container-id]");
    if (togglePassBtn && togglePassBtn.tagName === "BUTTON" && !copyBtn) {
      var containerId = togglePassBtn.getAttribute("data-container-id");
      var card = togglePassBtn.closest(".card");
      var input = card.querySelector(".cred-pass-input");
      if (input) {
        if (input.type === "password") {
          getCredentials(card)
            .then(function (password) {
              input.type = "text";
              input.value = password;
              togglePassBtn.textContent = __i18n.hidePassword;
              togglePassBtn.title = __i18n.hidePassword;
            })
            .catch(() => showToast(__i18n.cannotGetPwd, "error"));
        } else {
          input.type = "password";
          input.value = "●●●●●●●●●●●●";
          togglePassBtn.textContent = __i18n.showPassword;
          togglePassBtn.title = __i18n.showPassword;
        }
      }
    }

    var closePasswordButton = e.target.closest("[data-close-password-modal]");
    if (closePasswordButton) {
      var passwordModal = document.getElementById("passwordModal");
      if (passwordModal) {
        passwordModal.close();
      }
    }
  });

  // ============================================================
  // Ensure HTMX includes CSRF token in headers
  // ============================================================
  document.body.addEventListener("htmx:configRequest", (event) => {
    var csrfMeta = document.querySelector('meta[name="csrf-token"]');
    var csrf = csrfMeta ? csrfMeta.getAttribute("content") : "";
    if (csrf) {
      event.detail.headers["X-CSRF-Token"] = csrf;
    }
  });

  // ============================================================
  // Container Resource Stats (via WebSocket push + daisyUI progress)
  // ============================================================

  function getResourceLevel(pct, bar) {
    bar.classList.remove("progress-info", "progress-success", "progress-warning", "progress-error");
    if (pct >= 80) {
      bar.classList.add("progress-error");
    } else if (pct >= 50) {
      bar.classList.add("progress-warning");
    } else {
      bar.classList.add("progress-success");
    }
  }

  function updateResourceBars(stats) {
    Object.keys(stats).forEach(function (containerId) {
      var s = stats[containerId];
      var row = document.querySelector('[data-container-id="' + containerId + '"]');
      if (!row) return;
      var cell = row.querySelector(".resource-usage-cell");
      if (!cell) return;

      var cpuBar = cell.querySelector(".resource-bar-cpu");
      var cpuText = cell.querySelector(".resource-bar-cpu + .resource-bar-text");
      var memBar = cell.querySelector(".resource-bar-mem");
      var memText = cell.querySelector(".resource-bar-mem + .resource-bar-text");

      if (cpuBar) {
        var cpuPct = Math.min(s["cpu-pct"] || 0, 100);
        cpuBar.value = cpuPct;
        getResourceLevel(cpuPct, cpuBar);
      }
      if (cpuText) {
        cpuText.textContent = (s["cpu-pct"] != null ? s["cpu-pct"].toFixed(1) : "—") + "%";
      }
      if (memBar) {
        var memPct = Math.min(s["mem-pct"] || 0, 100);
        memBar.value = memPct;
        getResourceLevel(memPct, memBar);
      }
      if (memText) {
        var memInfo = s["mem-used"] && s["mem-used"] !== "—"
          ? s["mem-used"] + " / " + s["mem-limit"]
          : "—";
        memText.textContent = memInfo;
      }
    });
  }

  // Fallback: fetch stats once after docker-tbody HTMX swap
  document.body.addEventListener("htmx:afterSwap", function (evt) {
    if (evt.detail.target.id === "docker-tbody") {
      fetch("/docker/stats")
        .then(function (res) {
          if (!res.ok) throw new Error("stats fetch failed");
          return res.json();
        })
        .then(function (stats) {
          updateResourceBars(stats);
        })
        .catch(function () {
          // Silently ignore — WS stats push will update soon
        });
    }
    // Re-apply container filter after HTMX swaps admin-containers-tbody
    if (evt.detail.target.id === "admin-containers-tbody") {
      var activeBtn = document.querySelector("#admin-containers-tbody .filter-btn.pf-btn-info");
      if (activeBtn) {
        filterContainers(activeBtn.getAttribute("data-filter"));
      } else {
        applyDefaultContainerFilter();
      }
    }
  });

  // ============================================================
  // Admin Container Filter
  // ============================================================
  var currentContainerFilter = "running";

  function filterContainers(filter) {
    currentContainerFilter = filter;
    var tbody = document.getElementById("admin-containers-tbody");
    if (!tbody) return;

    var rows = tbody.querySelectorAll("tr[data-state]");
    rows.forEach(function (row) {
      if (filter === "all" || row.getAttribute("data-state") === filter) {
        row.classList.remove("hidden");
      } else {
        row.classList.add("hidden");
      }
    });

    // Update active button state
    var buttons = tbody.querySelectorAll(".filter-btn");
    buttons.forEach(function (btn) {
      if (btn.getAttribute("data-filter") === filter) {
        btn.classList.add("pf-btn-info");
      } else {
        btn.classList.remove("pf-btn-info");
      }
    });
  }

  function applyDefaultContainerFilter() {
    var btn = document.querySelector('#admin-containers-tbody .filter-btn[data-filter="running"]');
    if (btn) {
      filterContainers("running");
    }
  }

  // Delegated click handler for filter buttons
  document.body.addEventListener("click", function (e) {
    var btn = e.target.closest("#admin-containers-tbody .filter-btn");
    if (btn) {
      filterContainers(btn.getAttribute("data-filter"));
    }
  });

  applyDefaultContainerFilter();
});
