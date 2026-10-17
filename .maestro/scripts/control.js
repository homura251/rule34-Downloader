const response = http.get('http://127.0.0.1:18734/__control?' + CONTROL);
assert(response.status === 200, 'HTTP fixture control failed');
