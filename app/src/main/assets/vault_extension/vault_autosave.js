(function () {
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

  function handleSubmit(event) {
    var form = event.target;
    if (!form || !form.querySelectorAll) return;
    var passwordField = form.querySelector('input[type="password"]');
    if (!passwordField) return;
    var password = passwordField.value;
    if (!password) return;
    var user = userField(form, passwordField);
    var username = user ? user.value : "";
    var host = window.location.hostname || "";
    if (!host) return;
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

  document.addEventListener("submit", handleSubmit, true);
})();
