document.addEventListener("DOMContentLoaded", () => {
  var __i18n = {
    copied: "已复制 ✓",
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
    "admin-users-tbody": "refresh-admin",
    "admin-containers-tbody": "refresh-admin-containers",
  };

  function getWsToken() {
    var meta = document.querySelector('meta[name="ws-token"]');
    return meta ? meta.getAttribute("content") : "";
  }

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

  function connectWebSocket() {
    var protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    var token = getWsToken();
    var wsUrl = protocol + "//" + window.location.host + "/ws";
    if (token) {
      wsUrl += "?token=" + encodeURIComponent(token);
    }

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
        console.log("[ws] 连接已断开，" + wsReconnectDelay / 1000 + "s 后重连...");
        document.body.dispatchEvent(new CustomEvent("ws-status", {detail: {state: "reconnecting"}}));
        setTimeout(connectWebSocket, wsReconnectDelay);
        wsReconnectDelay = Math.min(wsReconnectDelay * 2, wsMaxReconnectDelay);
      };

      ws.onerror = function () {
        // onclose will fire after this, triggering reconnect
      };
    } catch (e) {
      console.warn("[ws] 连接失败，" + wsReconnectDelay / 1000 + "s 后重试");
      setTimeout(connectWebSocket, wsReconnectDelay);
      wsReconnectDelay = Math.min(wsReconnectDelay * 2, wsMaxReconnectDelay);
    }
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
  // Log viewer: ttyd popup window
  // ============================================================
  var activeLogContainerId = null;
  var logPollTimer = null;

  function stopLogPoll() {
    if (logPollTimer) {
      clearInterval(logPollTimer);
      logPollTimer = null;
    }
  }

  function stopLogTtyd() {
    if (activeLogContainerId) {
      var csrf = document.querySelector('meta[name="csrf-token"]').getAttribute("content");
      fetch("/docker/logs/stop", {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "X-CSRF-Token": csrf,
        },
        body: "id=" + encodeURIComponent(activeLogContainerId),
      }).catch(function () {});
      activeLogContainerId = null;
    }
    stopLogPoll();
  }

  function openLogViewer(containerId) {
    var csrf = document.querySelector('meta[name="csrf-token"]').getAttribute("content");
    fetch("/docker/logs/start", {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        "X-CSRF-Token": csrf,
      },
      body: "id=" + encodeURIComponent(containerId),
    })
      .then(function (res) { return res.json(); })
      .then(function (data) {
        if (data.error) {
          showToast(data.error, "error");
          return;
        }
        activeLogContainerId = containerId;
        // Open ttyd in a new popup window (same pattern as shell access)
        var logWindow = window.open(data.url, "_blank",
          "width=900,height=600,menubar=no,toolbar=no,location=yes,status=no");

        // Poll popup window: when user closes it, stop log ttyd
        stopLogPoll();
        logPollTimer = setInterval(function () {
          if (!logWindow || logWindow.closed) {
            stopLogTtyd();
          }
        }, 1000);
      })
      .catch(function (err) {
        showToast("日志查看器启动失败: " + err.message, "error");
      });
  }

  // Delegated click handler for log buttons
  document.body.addEventListener("click", function (e) {
    var logBtn = e.target.closest("[data-log-container]");
    if (logBtn) {
      e.preventDefault();
      e.stopPropagation();
      openLogViewer(logBtn.getAttribute("data-log-container"));
    }
  });

  // Cleanup on page unload
  window.addEventListener("beforeunload", function () {
    if (activeLogContainerId) {
      var csrf = document.querySelector('meta[name="csrf-token"]').getAttribute("content");
      navigator.sendBeacon(
        "/docker/logs/stop",
        new URLSearchParams({ id: activeLogContainerId, _csrf: csrf }).toString()
      );
    }
    stopLogPoll();
  });

  // ============================================================
  // Log viewer: show wrapper when content loads
  // ============================================================
  document.body.addEventListener("htmx:afterSwap", function (evt) {
    if (evt.detail.target.id === "password-modal-container") {
      var modal = document.getElementById("passwordModal");
      if (modal) {
        modal.showModal();
      }
    }
  });

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
              togglePassBtn.textContent = "👁️‍🗨️";
              togglePassBtn.title = __i18n.hidePassword;
            })
            .catch(() => showToast(__i18n.cannotGetPwd, "error"));
        } else {
          input.type = "password";
          input.value = "●●●●●●●●●●●●";
          togglePassBtn.textContent = "👁";
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
