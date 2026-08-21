(function () {
  var pending = {};

  function submit(host, username, password) {
    if (!host || !password) return;
    var key = host + "\u0001" + password;
    if (pending[key]) return;
    pending[key] = true;
    setTimeout(function () { pending[key] = false; }, 2000);
    try {
      browser.runtime.sendMessage({
        action: "AUTOSAVE_PROMPT",
        host: host,
        username: username,
        password: password
      });
    } catch (e) {
    }
  }

  function userField(form, passwordField) {
    var inputs = form.querySelectorAll("input");
    var candidates = [];
    for (var i = 0; i < inputs.length; i++) {
      var input = inputs[i];
      if (input === passwordField) break;
      var type = (input.type || "").toLowerCase();
      if (type === "hidden" || type === "submit" || type === "button"
          || type === "reset" || type === "checkbox" || type === "radio") continue;
      if (type === "password") continue;
      if (type === "email" || type === "text" || type === "tel") candidates.push(input);
    }
    var meta = candidates.filter(function (input) {
      var hay = ((input.name || "") + " " + (input.id || "")
          + " " + (input.autocomplete || "")).toLowerCase();
      return /user|login|email|account|name|id/i.test(hay);
    });
    if (meta.length > 0) return meta[0];
    return candidates.length > 0 ? candidates[candidates.length - 1] : null;
  }

  function handleForm(form) {
    if (!form || !form.querySelectorAll) return;
    var passwordField = form.querySelector('input[type="password"]');
    if (!passwordField) return;
    var password = passwordField.value;
    if (!password) return;
    var user = userField(form, passwordField);
    var username = user ? user.value : "";
    submit(window.location.hostname || "", username, password);
  }

  document.addEventListener("submit", function (event) {
    handleForm(event.target);
  }, true);

  document.addEventListener("click", function (event) {
    var el = event.target;
    if (!el || !el.tagName) return;
    if (el.tagName === "BUTTON"
        || (el.tagName === "INPUT"
            && (el.type === "submit" || el.type === "button"))) {
      var form = el.form || el.closest("form");
      if (form) setTimeout(function () { handleForm(form); }, 300);
    }
  }, true);

  document.addEventListener("keydown", function (event) {
    if (event.key !== "Enter") return;
    var el = event.target;
    if (el && el.type === "password") {
      var form = el.form || el.closest("form");
      if (form) setTimeout(function () { handleForm(form); }, 300);
    }
  }, true);
})();
