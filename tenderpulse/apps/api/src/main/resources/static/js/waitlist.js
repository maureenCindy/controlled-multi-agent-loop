(function () {
  "use strict";

  // Deliberately conservative — good enough to block obviously-malformed input
  // client-side; the server (WaitlistRequest @Email) is the source of truth.
  var EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

  var form = document.getElementById("waitlist-form-el");
  if (!form) {
    return;
  }

  var emailInput = document.getElementById("email");
  var emailError = document.getElementById("email-error");
  var sectorSelect = document.getElementById("sector");
  var provinceInput = document.getElementById("province");
  var submitButton = document.getElementById("submit-button");
  var formStatus = document.getElementById("form-status");
  var successMessage = document.getElementById("success-message");

  function showEmailError(message) {
    emailError.textContent = message;
    emailError.hidden = false;
    emailInput.setAttribute("aria-invalid", "true");
  }

  function clearEmailError() {
    emailError.textContent = "";
    emailError.hidden = true;
    emailInput.removeAttribute("aria-invalid");
  }

  function showStatus(message, kind) {
    formStatus.textContent = message;
    formStatus.hidden = false;
    formStatus.className = "form-status " + kind;
  }

  function clearStatus() {
    formStatus.textContent = "";
    formStatus.hidden = true;
    formStatus.className = "form-status";
  }

  function isValidEmail(value) {
    return EMAIL_PATTERN.test(value.trim());
  }

  function validate() {
    var email = emailInput.value.trim();
    if (!email) {
      showEmailError("Email is required.");
      return false;
    }
    if (!isValidEmail(email)) {
      showEmailError("Please enter a valid email address.");
      return false;
    }
    clearEmailError();
    return true;
  }

  emailInput.addEventListener("blur", validate);
  emailInput.addEventListener("input", function () {
    if (!emailError.hidden) {
      validate();
    }
  });

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    clearStatus();

    // Client-side gate: invalid/missing email blocks submission entirely —
    // no network call is made until this passes.
    if (!validate()) {
      emailInput.focus();
      return;
    }

    var payload = {
      email: emailInput.value.trim(),
      sectors: sectorSelect.value ? [sectorSelect.value] : [],
      province: provinceInput.value.trim() || null
    };

    submitButton.disabled = true;
    showStatus("Submitting…", "");

    fetch("/api/v1/waitlist", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("Request failed with status " + response.status);
        }
        return response.json();
      })
      .then(function () {
        form.hidden = true;
        clearStatus();
        successMessage.hidden = false;
      })
      .catch(function () {
        showStatus(
          "Something went wrong submitting your details. Please try again.",
          "error"
        );
      })
      .finally(function () {
        submitButton.disabled = false;
      });
  });
})();
