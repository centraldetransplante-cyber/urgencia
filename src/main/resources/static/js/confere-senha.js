// Bloqueia o submit de um form de troca/definicao de senha quando "Nova
// senha" e "Confirmar nova senha" nao conferem - compartilhado entre
// usuarios/minha-senha.html e usuarios/redefinir-senha.html (mesmos ids de
// campo: novaSenha, confirmacao, msgConfere). Extraido de script inline em
// 2026-08-24 (bug_005 da revisao de codigo do PR de reset de senha por
// token) - JS especifico nunca fica inline nos templates (ver CLAUDE.md,
// "Convencoes de codigo").
(function () {
    var nova = document.getElementById('novaSenha');
    var conf = document.getElementById('confirmacao');
    var msg = document.getElementById('msgConfere');
    if (!nova || !conf) {
        return;
    }
    var form = nova.closest('form');
    if (!form) {
        return;
    }
    form.addEventListener('submit', function (e) {
        if (nova.value !== conf.value) {
            e.preventDefault();
            if (msg) {
                msg.classList.remove('d-none');
            }
            conf.focus();
        }
    });
})();
