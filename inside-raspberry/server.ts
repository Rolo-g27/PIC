import express, { Request, Response } from 'express';
import { execFile } from 'child_process';
import path from 'path';

const app = express();
app.use(express.json());

// Diz ao servidor para apresentar os ficheiros visuais (HTML/CSS) da pasta "public"
app.use(express.static(path.join(__dirname, 'public')));

// --- ROTA 1: VERIFICAR PIN ---
app.post('/api/verificar-pin', (req: Request, res: Response) => {
    const pin: string = req.body.pin;

    execFile('./rasp', ['--verify-pin', pin], (error, stdout, stderr) => {
        try {
            const respostaCartao = JSON.parse(stdout);
            res.json(respostaCartao);
        } catch (e) {
            // Atualizado para usar 'estado' e 'mensagem' em vez de 'status' e 'message'
            res.status(500).json({ estado: "erro", mensagem: "O C não devolveu um JSON válido." });
        }
    });
});

// --- ROTA 2: DESCARREGAR E APAGAR FICHEIRO ---
app.post('/api/descarregar', (req: Request, res: Response) => {
    const pin: string = req.body.pin;

    // Repara que aqui chamamos o '--download' e passamos o PIN novamente
    execFile('./rasp', ['--download', pin], (error, stdout, stderr) => {
        try {
            const respostaCartao = JSON.parse(stdout);
            res.json(respostaCartao);
        } catch (e) {
            res.status(500).json({ estado: "erro", mensagem: "Erro ao ler a extração do cartão." });
        }
    });
});

app.listen(3000, () => {
    console.log('[+] Interface visual pronta! Abre http://localhost:3000 no browser.');
});