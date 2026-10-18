const response = http.get('http://127.0.0.1:18734/__control?' + CONTROL);
if (Number(response.status) !== 200) throw new Error('HTTP fixture control failed');
