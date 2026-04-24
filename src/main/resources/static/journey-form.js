document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll("[data-template-fill]").forEach((button) => {
        button.addEventListener("click", () => {
            setFieldValue("message", button.dataset.message);
            setFieldValue("destination", button.dataset.destination);
            setFieldValue("departure", button.dataset.departure);
            setFieldValue("travelDays", button.dataset.travelDays);
            setFieldValue("budget", button.dataset.budget);
            setFieldValue("preferences", button.dataset.preferences);
            document.getElementById("message")?.focus();
        });
    });
});

function setFieldValue(id, value) {
    const element = document.getElementById(id);
    if (element) {
        element.value = value || "";
    }
}
